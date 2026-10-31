package com.example.myapplication.pw11.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.Interceptor
import okhttp3.Response

/** Сховище токена. Інтерфейс дозволяє підставити реалізацію в пам'яті для тестів. */
interface TokenStorage {
    suspend fun read(): String?
    suspend fun save(token: String)
    suspend fun clear()
}

private val Context.authDataStore by preferencesDataStore(name = "pw11_auth")

/**
 * Збереження токена в DataStore Preferences: асинхронно (корутини), без блокування UI-потоку,
 * і токен переживає перезапуск застосунку. У реальному застосунку значення варто додатково
 * шифрувати (наприклад, ключем з Android Keystore).
 */
class DataStoreTokenStorage(private val context: Context) : TokenStorage {
    private val key = stringPreferencesKey("access_token")

    override suspend fun read(): String? = context.authDataStore.data.map { it[key] }.first()

    override suspend fun save(token: String) {
        context.authDataStore.edit { it[key] = token }
    }

    override suspend fun clear() {
        context.authDataStore.edit { it.remove(key) }
    }
}

class InMemoryTokenStorage(private var token: String? = null) : TokenStorage {
    override suspend fun read() = token
    override suspend fun save(token: String) {
        this.token = token
    }
    override suspend fun clear() {
        token = null
    }
}

/**
 * OkHttp Interceptor: автоматично додає «Authorization: Bearer <token>» до кожного запиту.
 *
 * Токен береться з [tokenProvider] (поточна сесія в пам'яті Repository), тому Interceptor
 * не звертається до DataStore і не блокує мережевий потік. Не змінюються запити:
 *  - зі службовим заголовком «No-Auth» (логін; демонстрація запиту без токена) — заголовок видаляється;
 *  - які вже мають Authorization (демонстрація підробленого токена).
 */
class AuthInterceptor(private val tokenProvider: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(NO_AUTH) != null) {
            return chain.proceed(request.newBuilder().removeHeader(NO_AUTH).build())
        }
        if (request.header("Authorization") != null) return chain.proceed(request)
        val token = tokenProvider() ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }

    companion object {
        const val NO_AUTH = "No-Auth"
    }
}
