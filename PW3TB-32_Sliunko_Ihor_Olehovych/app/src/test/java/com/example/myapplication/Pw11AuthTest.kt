package com.example.myapplication

import com.example.myapplication.net.buildRetrofit
import com.example.myapplication.pw11.auth.Jwt
import com.example.myapplication.pw11.auth.JwtClaims
import com.example.myapplication.pw11.auth.Role
import com.example.myapplication.pw11.data.AuthError
import com.example.myapplication.pw11.data.AuthInterceptor
import com.example.myapplication.pw11.data.AuthRepository
import com.example.myapplication.pw11.data.InMemoryTokenStorage
import com.example.myapplication.pw11.data.remote.AuthApi
import com.example.myapplication.pw11.data.remote.MockAuthServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Pw11AuthTest {
    private var clock = 1_800_000_000_000L
    private val storage = InMemoryTokenStorage()
    private lateinit var repo: AuthRepository

    init {
        val retrofit = buildRetrofit(MockAuthServer(nowMs = { clock }, latencyMs = 0), AuthInterceptor { repo.current?.token })
        repo = AuthRepository(retrofit.create(AuthApi::class.java), storage, nowMs = { clock })
    }

    private inline fun <reified E : AuthError> Result<*>.assertError(): E {
        val e = exceptionOrNull()
        assertTrue("Очікувалось ${E::class.simpleName}, отримано $e", e is E)
        return e as E
    }

    @Test
    fun pw11_jwtEncodeDecodeVerify() {
        val claims = JwtClaims("operator", "Олена", "OPERATOR", 100, 400)
        val token = Jwt.encode(claims, "secret")
        assertEquals(3, token.split('.').size)
        assertEquals(claims, Jwt.decode(token))
        assertEquals(Jwt.Verification.Valid(claims), Jwt.verify(token, "secret", nowSec = 200))
        assertTrue(Jwt.verify(token, "secret", nowSec = 400) is Jwt.Verification.Expired)
        assertEquals(Jwt.Verification.BadSignature, Jwt.verify(token, "other-secret", nowSec = 200))
        assertEquals(Jwt.Verification.Malformed, Jwt.verify("abc", "secret", nowSec = 200))
    }

    @Test
    fun pw11_wrongPasswordAndLockout() = runBlocking {
        repeat(2) {
            assertEquals("Невірний логін або пароль", repo.login("operator", "wrong!", 60).assertError<AuthError.InvalidCredentials>().message)
        }
        repo.login("operator", "wrong!", 60).assertError<AuthError.InvalidCredentials>()
        // Після 3 невдалих спроб навіть правильний пароль відхиляється (429) до спливу 30 с
        repo.login("operator", "oper123", 60).assertError<AuthError.Locked>()
        clock += 31_000
        assertTrue(repo.login("operator", "oper123", 60).isSuccess)
    }

    @Test
    fun pw11_tokenIsStoredAndRolesAreEnforcedByServer() = runBlocking {
        val session = repo.login("viewer", "view123", 120).getOrThrow()
        assertEquals(Role.VIEWER, session.claims.roleOrNull)
        assertEquals(session.token, storage.read())                     // токен збережено в сховищі
        assertEquals(120, session.claims.expiresAt - session.claims.issuedAt)

        assertEquals(3, repo.call { it.reserveStatus() }.getOrThrow().units?.size) // Interceptor підставив токен
        repo.call { it.runAtsTest() }.assertError<AuthError.Forbidden>()          // 403: інспектор не має прав
        repo.call { it.audit() }.assertError<AuthError.Forbidden>()

        repo.login("operator", "oper123", 120).getOrThrow()
        assertEquals("Олена Коваль", repo.call { it.runAtsTest() }.getOrThrow().startedBy)
        repo.call { it.audit() }.assertError<AuthError.Forbidden>()

        repo.login("admin", "admin123", 120).getOrThrow()
        val audit = repo.call { it.audit() }.getOrThrow()
        assertTrue(audit.any { it.action == "Тест АВР (немає прав)" && it.success == false })
    }

    @Test
    fun pw11_missingOrForgedTokenIsRejected() = runBlocking {
        repo.login("viewer", "view123", 120).getOrThrow()
        assertEquals("token_missing", repo.call { it.reserveStatusAnonymous() }.assertError<AuthError.Unauthorized>().reason)
        val forged = repo.forgedToken()!!
        assertEquals("ADMIN", Jwt.decode(forged)?.role)                // клієнт «підвищив» роль
        assertEquals("token_invalid", repo.call { it.reserveStatusWithToken("Bearer $forged") }.assertError<AuthError.Unauthorized>().reason)

        // Для адміністратора підробка теж відхиляється: змінено exp, тож підпис не збігається
        repo.login("admin", "admin123", 120).getOrThrow()
        val forgedAdmin = repo.forgedToken()!!
        assertEquals("token_invalid", repo.call { it.reserveStatusWithToken("Bearer $forgedAdmin") }.assertError<AuthError.Unauthorized>().reason)
    }

    @Test
    fun pw11_expiredTokenRequiresNewLogin() = runBlocking {
        repo.login("operator", "oper123", 15).getOrThrow()
        clock += 16_000
        assertEquals("token_expired", repo.call { it.reserveStatus() }.assertError<AuthError.Unauthorized>().reason)
        assertTrue(repo.restore().isFailure)                            // при запуску прострочений токен видаляється
        assertNull(storage.read())
        assertNull(repo.current)
    }

    @Test
    fun pw11_sessionIsRestoredFromStorage() = runBlocking {
        val session = repo.login("admin", "admin123", 300).getOrThrow()
        repo.logout()
        assertNull(storage.read())
        storage.save(session.token)
        assertEquals("admin", repo.restore().getOrThrow()?.claims?.subject)
    }
}
