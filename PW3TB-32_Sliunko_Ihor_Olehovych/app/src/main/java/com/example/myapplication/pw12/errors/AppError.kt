package com.example.myapplication.pw12.errors

import com.example.myapplication.net.ErrorBody
import com.example.myapplication.net.MockHttp
import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import retrofit2.HttpException

/**
 * Типи помилок застосунку. UI отримує лише [title] / [userMessage] і не знає про винятки Retrofit/OkHttp.
 * [retryable] — чи має сенс автоматичний повтор (тимчасова проблема мережі або сервера).
 */
sealed class AppError(val title: String, val userMessage: String, val retryable: Boolean) {

    /** Немає підключення до мережі — запит навіть не надсилається. */
    data object NoInternet : AppError(
        "Немає з'єднання", "Відсутнє з'єднання з інтернетом. Перевірте Wi-Fi або мобільні дані.", retryable = false
    )

    /** Таймаут — тимчасова проблема, запит повторюється. */
    data object Timeout : AppError(
        "Час очікування вичерпано", "Сервер не відповів вчасно. Повторюємо запит…", retryable = true
    )

    /** Помилка мережі без відповіді сервера (обрив з'єднання, DNS). */
    class Network(detail: String?) : AppError(
        "Помилка мережі", "З'єднання з сервером перервано${detail?.let { ": $it" } ?: ""}", retryable = true
    )

    /** Сервер відповів HTTP-кодом помилки — повідомлення залежить від коду. */
    class Http(val code: Int, val serverMessage: String?, val retryAfterSec: Long?) : AppError(
        title = "HTTP $code",
        userMessage = messageFor(code, retryAfterSec),
        retryable = code == 408 || code == 429 || code >= 500,
    )

    /** Відповідь не відповідає очікуваній структурі JSON. */
    class Parse(detail: String?) : AppError(
        "Некоректна відповідь", "Сервер повернув дані в неочікуваному форматі${detail?.let { " ($it)" } ?: ""}", retryable = false
    )

    /** Некоректне введення користувача — запит не виконується. */
    class InvalidInput(message: String) : AppError("Некоректні дані", message, retryable = false)

    class Unknown(t: Throwable) : AppError("Непередбачена помилка", t.message ?: t::class.java.simpleName, retryable = false)

    /** Короткий опис для журналу. */
    open val logLabel: String
        get() = when (this) {
            is Http -> "HTTP $code ${MockHttp.reason(code)}"
            else -> this::class.java.simpleName
        }

    companion object {
        fun messageFor(code: Int, retryAfterSec: Long? = null) = when (code) {
            400 -> "Некоректний запит — перевірте введені дані"
            401 -> "Сесія завершилась: потрібно увійти повторно"
            403 -> "Недостатньо прав для цієї операції"
            404 -> "Об'єкт не знайдено на сервері — перевірте код об'єкта"
            408 -> "Сервер не дочекався запиту, повторюємо"
            429 -> "Забагато запитів — повтор через ${retryAfterSec ?: 1} с"
            500 -> "Сталася помилка на сервері, спробуйте пізніше"
            502, 503, 504 -> "Сервер тимчасово недоступний, повторюємо запит"
            in 400..499 -> "Помилка запиту (код $code)"
            else -> "Помилка сервера (код $code)"
        }
    }
}

/**
 * Централізований обробник помилок: усі винятки мережевого шару перетворюються на [AppError]
 * в одному місці, і кожна помилка реєструється в журналі ([ErrorLogger]).
 * Завдяки цьому ViewModel і UI не містять try/catch для конкретних типів винятків.
 */
class ErrorHandler(private val logger: ErrorLogger) {

    fun classify(t: Throwable): AppError = when (t) {
        is AppException -> t.error
        is SocketTimeoutException -> AppError.Timeout
        is InterruptedIOException -> AppError.Timeout // OkHttp callTimeout
        is UnknownHostException -> AppError.NoInternet
        is HttpException -> {
            val response = t.response()
            val body = runCatching {
                MockHttp.gson.fromJson(response?.errorBody()?.string(), ErrorBody::class.java)
            }.getOrNull()
            AppError.Http(t.code(), body?.message, response?.headers()?.get("Retry-After")?.toLongOrNull())
        }
        is JsonParseException -> AppError.Parse(t.cause?.message ?: t.message)
        // Пошкоджений або обірваний JSON: Gson кидає ці підкласи IOException — це не помилка мережі
        is MalformedJsonException, is EOFException -> AppError.Parse(t.message)
        // Текст у числовому полі: Gson передає NumberFormatException без обгортки
        is NumberFormatException -> AppError.Parse(t.message)
        is IOException -> AppError.Network(t.message)
        else -> AppError.Unknown(t)
    }

    /** Записати вже класифіковану помилку в журнал; [nextDelayMs] = null — повтору не буде. */
    fun logClassified(error: AppError, request: String, attempt: Int, maxAttempts: Int, nextDelayMs: Long?) {
        logger.log(
            request = request,
            error = error.logLabel,
            details = (error as? AppError.Http)?.serverMessage ?: error.userMessage,
            attempt = "$attempt/$maxAttempts",
            action = nextDelayMs?.let { "повтор через ${it / 1000} с" } ?: "без повтору",
        )
    }

    /** Класифікувати й одразу зареєструвати (для помилок поза політикою повторів). */
    fun handle(t: Throwable, request: String): AppError =
        classify(t).also { logClassified(it, request, 1, 1, null) }
}

/** Виняток із вже класифікованою помилкою (наприклад, NoInternet до відправки запиту). */
class AppException(val error: AppError) : Exception(error.userMessage)
