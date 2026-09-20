package com.example.myapplication.pw4.data

/** Надійність пароля для індикатора під полем. */
enum class PasswordStrength(val title: String, val fraction: Float) {
    WEAK("Слабкий", 0.33f),
    MEDIUM("Середній", 0.66f),
    STRONG("Надійний", 1f),
}

/**
 * Бізнес-логіка перевірки форм авторизації та реєстрації.
 * Кожна функція повертає текст помилки або null, якщо значення коректне —
 * так UI може напряму передавати результат у supportingText / isError.
 */
object AuthValidator {
    const val MIN_PASSWORD_LENGTH = 8

    private val emailRegex = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    fun validateEmail(email: String): String? = when {
        email.isBlank() -> "Введіть e-mail"
        !emailRegex.matches(email.trim()) -> "Некоректний формат e-mail"
        else -> null
    }

    /** Під час входу перевіряємо лише непорожність: правила складності діють при реєстрації. */
    fun validateLoginPassword(password: String): String? =
        if (password.isEmpty()) "Введіть пароль" else null

    fun validateFullName(name: String): String? {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "Введіть прізвище та ім'я"
            parts.size < 2 -> "Вкажіть прізвище та ім'я повністю"
            parts.any { it.length < 2 } -> "Кожна частина імені має містити щонайменше 2 літери"
            else -> null
        }
    }

    fun validateNewPassword(password: String): String? = when {
        password.length < MIN_PASSWORD_LENGTH -> "Щонайменше $MIN_PASSWORD_LENGTH символів"
        password.none { it.isLetter() } -> "Додайте хоча б одну літеру"
        password.none { it.isDigit() } -> "Додайте хоча б одну цифру"
        else -> null
    }

    fun validateConfirmPassword(password: String, confirm: String): String? = when {
        confirm.isEmpty() -> "Повторіть пароль"
        confirm != password -> "Паролі не збігаються"
        else -> null
    }

    fun validateTermsAccepted(accepted: Boolean): String? =
        if (accepted) null else "Підтвердьте ознайомлення з регламентом"

    /** Оцінка надійності: довжина, регістри, цифри та спецсимволи. */
    fun passwordStrength(password: String): PasswordStrength {
        var score = 0
        if (password.length >= MIN_PASSWORD_LENGTH) score++
        if (password.length >= 12) score++
        if (password.any { it.isUpperCase() } && password.any { it.isLowerCase() }) score++
        if (password.any { it.isDigit() }) score++
        if (password.any { !it.isLetterOrDigit() }) score++
        return when {
            score <= 2 -> PasswordStrength.WEAK
            score <= 3 -> PasswordStrength.MEDIUM
            else -> PasswordStrength.STRONG
        }
    }
}
