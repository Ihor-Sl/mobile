package com.example.myapplication.pw12.errors

import kotlinx.coroutines.delay

/** Інформація про чергову невдалу спробу — ViewModel показує її в UI. */
data class RetryInfo(val attempt: Int, val maxAttempts: Int, val error: AppError, val nextDelayMs: Long)

/**
 * Політика автоматичного повтору запиту при тимчасових помилках.
 *
 *  - повторюються лише помилки з [AppError.retryable] (таймаут, обрив з'єднання, 408, 429, 5xx);
 *  - затримка росте експоненційно: 1 → 2 → 4 с (не більше [maxDelayMs]);
 *  - для 429 / 503 використовується заголовок Retry-After, якщо сервер його передав;
 *  - кожна невдала спроба проходить через [ErrorHandler] і реєструється в журналі.
 */
class RetryPolicy(
    val maxAttempts: Int = 4,
    private val baseDelayMs: Long = 1_000,
    private val factor: Double = 2.0,
    private val maxDelayMs: Long = 8_000,
) {
    fun delayFor(attempt: Int, error: AppError): Long {
        val retryAfter = (error as? AppError.Http)?.retryAfterSec
        if (retryAfter != null) return (retryAfter * 1000).coerceAtMost(maxDelayMs)
        var d = baseDelayMs.toDouble()
        repeat(attempt - 1) { d *= factor }
        return d.toLong().coerceAtMost(maxDelayMs)
    }

    /**
     * Виконує [block]; у разі тимчасової помилки чекає й повторює. Повертає результат або
     * кидає [AppException] з остаточно класифікованою помилкою.
     */
    suspend fun <T> execute(
        request: String,
        handler: ErrorHandler,
        onRetry: (RetryInfo) -> Unit = {},
        onAttempt: (Int) -> Unit = {},
        onCountdown: (Int) -> Unit = {},
        block: suspend () -> T,
    ): T {
        var attempt = 1
        while (true) {
            onAttempt(attempt)
            try {
                return block()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val classified = handler.classify(e)
                val willRetry = classified.retryable && attempt < maxAttempts
                val wait = if (willRetry) delayFor(attempt, classified) else null
                // Журнал отримує кожну невдалу спробу (класифікація повторно не читає тіло відповіді)
                handler.logClassified(classified, request, attempt, maxAttempts, wait)
                if (!willRetry) throw AppException(classified)
                onRetry(RetryInfo(attempt, maxAttempts, classified, wait!!))
                // Пауза кроками по 1 с — UI показує зворотний відлік до наступної спроби
                var left = wait
                while (left > 0) {
                    onCountdown(((left + 999) / 1000).toInt())
                    val step = minOf(1_000L, left)
                    delay(step)
                    left -= step
                }
                attempt++
            }
        }
    }
}
