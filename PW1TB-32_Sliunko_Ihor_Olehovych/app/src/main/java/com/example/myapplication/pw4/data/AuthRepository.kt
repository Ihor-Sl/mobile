package com.example.myapplication.pw4.data

import java.security.MessageDigest
import kotlinx.coroutines.delay

/** Ролі персоналу системи аварійного електропостачання. */
enum class OperatorRole(val title: String) {
    DISPATCHER("Диспетчер"),
    ENGINEER("Інженер"),
    ADMIN("Адмін"),
}

data class Account(val fullName: String, val email: String, val role: OperatorRole)

/** Результат мережевої операції авторизації/реєстрації. */
sealed interface AuthResult {
    data class Success(val account: Account) : AuthResult
    data class Failure(val message: String) : AuthResult
}

/**
 * Імітація серверної частини: акаунти зберігаються в пам'яті процесу, а затримка [NETWORK_DELAY_MS]
 * моделює мережевий запит, під час якого UI показує стан завантаження.
 * Публічні suspend-функції login/register не залежать від реалізації — реальний бекенд
 * можна підставити без змін в UI.
 */
object FakeAuthRepository {
    const val DEMO_EMAIL = "dispatcher@power.ua"
    const val DEMO_PASSWORD = "Passw@rd"

    private const val NETWORK_DELAY_MS = 1_200L

    private class StoredAccount(val account: Account, val passwordHash: String)

    // Демо-акаунт, щоб можна було перевірити вхід без реєстрації
    private val accounts = mutableMapOf(
        DEMO_EMAIL to StoredAccount(
            Account("Коваленко Олена", DEMO_EMAIL, OperatorRole.DISPATCHER),
            hash(DEMO_PASSWORD)
        )
    )

    suspend fun login(email: String, password: String): AuthResult {
        delay(NETWORK_DELAY_MS)
        val stored = accounts[email.trim().lowercase()]
        return if (stored != null && stored.passwordHash == hash(password)) {
            AuthResult.Success(stored.account)
        } else {
            AuthResult.Failure("Невірний e-mail або пароль")
        }
    }

    suspend fun register(fullName: String, email: String, role: OperatorRole, password: String): AuthResult {
        delay(NETWORK_DELAY_MS)
        val key = email.trim().lowercase()
        if (accounts.containsKey(key)) {
            return AuthResult.Failure("Користувач із таким e-mail уже зареєстрований")
        }
        val account = Account(fullName.trim(), key, role)
        accounts[key] = StoredAccount(account, hash(password))
        return AuthResult.Success(account)
    }

    private fun hash(password: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(password.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
