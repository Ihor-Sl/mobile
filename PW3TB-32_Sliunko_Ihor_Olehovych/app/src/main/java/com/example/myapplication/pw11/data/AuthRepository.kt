package com.example.myapplication.pw11.data

import android.content.Context
import com.example.myapplication.net.ErrorBody
import com.example.myapplication.net.MockHttp
import com.example.myapplication.net.buildRetrofit
import com.example.myapplication.pw11.auth.Jwt
import com.example.myapplication.pw11.auth.JwtClaims
import com.example.myapplication.pw11.data.remote.AuthApi
import com.example.myapplication.pw11.data.remote.LoginRequest
import com.example.myapplication.pw11.data.remote.MockAuthServer
import java.io.IOException
import okio.ByteString.Companion.encodeUtf8
import retrofit2.HttpException

/** Поточна сесія: сам токен і розібраний з нього payload. */
data class Session(val token: String, val claims: JwtClaims)

/** Помилки автентифікації та доступу, зрозумілі для UI. */
sealed class AuthError(message: String) : Exception(message) {
    class InvalidCredentials(message: String) : AuthError(message)
    class Locked(message: String) : AuthError(message)
    /** 401: токена немає / недійсний / прострочений — потрібен повторний вхід. */
    class Unauthorized(val reason: String, message: String) : AuthError(message)
    /** 403: токен дійсний, але роль не має прав на дію. */
    class Forbidden(message: String) : AuthError(message)
    class Network(message: String) : AuthError(message)
    class Server(message: String) : AuthError(message)
}

/**
 * Repository автентифікації.
 *
 * Логіка управління токеном:
 *  1. [login] отримує JWT від сервера, зберігає його в [storage] (DataStore) і в пам'яті ([current]);
 *  2. [AuthInterceptor] бере токен з [current] і додає його до кожного запиту;
 *  3. [restore] при запуску читає токен із DataStore і перевіряє термін дії за полем exp;
 *  4. відповіді 401/403 перетворюються на [AuthError], щоб ViewModel вирішила: вийти чи показати повідомлення.
 */
class AuthRepository(
    private val api: AuthApi,
    private val storage: TokenStorage,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    var current: Session? = null
        private set

    /** Відновлення сесії. null — токена немає; прострочений токен видаляється. */
    suspend fun restore(): Result<Session?> {
        val token = storage.read() ?: return Result.success(null)
        val claims = Jwt.decode(token)
        if (claims == null || claims.isExpired(nowMs() / 1000)) {
            logout()
            return Result.failure(AuthError.Unauthorized("token_expired", "Термін дії збереженого токена минув — увійдіть повторно"))
        }
        return Result.success(Session(token, claims).also { current = it })
    }

    suspend fun login(username: String, password: String, ttlSec: Int): Result<Session> = call {
        val response = api.login(LoginRequest(username.trim(), password, ttlSec))
        val token = response.accessToken ?: throw AuthError.Server("Сервер не повернув токен")
        val claims = Jwt.decode(token) ?: throw AuthError.Server("Сервер повернув некоректний токен")
        storage.save(token)
        Session(token, claims).also { current = it }
    }

    suspend fun logout() {
        current = null
        storage.clear()
    }

    /** Виконання запиту до захищеного ресурсу з єдиною обробкою помилок. */
    suspend fun <T> call(block: suspend (AuthApi) -> T): Result<T> = try {
        Result.success(block(api))
    } catch (e: AuthError) {
        Result.failure(e)
    } catch (e: HttpException) {
        Result.failure(map(e))
    } catch (e: IOException) {
        Result.failure(AuthError.Network("Немає зв'язку з сервером: ${e.message}"))
    }

    /**
     * Підроблений токен: у payload роль замінено на ADMIN і термін дії подовжено на добу,
     * а підпис залишився старим — сервер має його відхилити.
     */
    fun forgedToken(): String? {
        val session = current ?: return null
        val parts = session.token.split('.')
        val forged = MockHttp.gson.toJson(session.claims.copy(role = "ADMIN", expiresAt = session.claims.expiresAt + 86_400)).encodeUtf8().base64Url().trimEnd('=')
        return "${parts[0]}.$forged.${parts[2]}"
    }

    private fun map(e: HttpException): AuthError {
        val body = runCatching {
            MockHttp.gson.fromJson(e.response()?.errorBody()?.string(), ErrorBody::class.java)
        }.getOrNull()
        val message = body?.message ?: "HTTP ${e.code()}"
        return when (e.code()) {
            401 -> if (body?.error == "invalid_credentials") AuthError.InvalidCredentials(message)
            else AuthError.Unauthorized(body?.error ?: "unauthorized", message)
            403 -> AuthError.Forbidden(message)
            429 -> AuthError.Locked(message)
            else -> AuthError.Server(message)
        }
    }

    companion object {
        @Volatile
        private var instance: AuthRepository? = null

        fun create(context: Context): AuthRepository =
            instance ?: synchronized(this) {
                instance ?: run {
                    lateinit var repository: AuthRepository
                    // Interceptor читає токен з поточної сесії Repository
                    val retrofit = buildRetrofit(MockAuthServer(), AuthInterceptor { repository.current?.token })
                    repository = AuthRepository(retrofit.create(AuthApi::class.java), DataStoreTokenStorage(context.applicationContext))
                    repository
                }.also { instance = it }
            }
    }
}
