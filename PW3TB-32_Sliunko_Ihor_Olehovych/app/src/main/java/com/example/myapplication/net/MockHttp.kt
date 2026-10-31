package com.example.myapplication.net

import com.google.gson.Gson
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Базова адреса API диспетчерської системи аварійного електропостачання. */
object ApiConfig {
    const val BASE_URL = "https://emergency-power.kpi.example/api/"
}

/**
 * Інструменти для імітаційних (mocked) серверів.
 *
 * Сервер реалізовано як останній OkHttp [Interceptor] у ланцюжку: він не передає запит у мережу,
 * а сам формує HTTP-відповідь із JSON. Завдяки цьому Retrofit, Gson, інші перехоплювачі
 * (авторизація, логування) і вся обробка HTTP-кодів працюють так само, як із реальним сервером,
 * а застосунок можна запускати без бекенду.
 */
object MockHttp {
    private val JSON = "application/json; charset=utf-8".toMediaType()
    val gson = Gson()

    /** Відповідь із довільним тілом: об'єкт серіалізується Gson, рядок передається як є. */
    fun json(request: Request, code: Int, body: Any?, headers: Map<String, String> = emptyMap()): Response {
        val text = when (body) {
            null -> ""
            is String -> body
            else -> gson.toJson(body)
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(reason(code))
            .body(text.toResponseBody(JSON))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
    }

    /** Стандартне тіло помилки: {"error": "...", "message": "..."}. */
    fun error(request: Request, code: Int, error: String, message: String, headers: Map<String, String> = emptyMap()) =
        json(request, code, ErrorBody(error, message), headers)

    /** Сегменти шляху після «/api/», наприклад [tanks, TNK-01]. */
    fun path(request: Request): List<String> =
        request.url.pathSegments.dropWhile { it != "api" }.drop(1).filter { it.isNotEmpty() }

    fun bodyOf(request: Request): String? = request.body?.let { body ->
        Buffer().also { body.writeTo(it) }.readUtf8()
    }

    fun reason(code: Int) = when (code) {
        200 -> "OK"
        201 -> "Created"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        408 -> "Request Timeout"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "HTTP $code"
    }

    /** Імітація затримки мережі (виконується в потоці OkHttp, а не в головному потоці). */
    fun latency(ms: Long) {
        if (ms > 0) Thread.sleep(ms)
    }
}

/** Тіло помилки, яке повертають імітаційні сервери. */
data class ErrorBody(val error: String?, val message: String?)

/**
 * Створює Retrofit: перехоплювачі [interceptors] (наприклад, авторизація) виконуються першими,
 * [server] — останнім, він і формує відповідь. Gson-конвертер перетворює JSON у Kotlin-об'єкти.
 */
fun buildRetrofit(server: Interceptor, vararg interceptors: Interceptor, timeoutSec: Long = 10): Retrofit {
    val client = OkHttpClient.Builder()
        .apply { interceptors.forEach { addInterceptor(it) } }
        .addInterceptor(server)
        .callTimeout(timeoutSec, TimeUnit.SECONDS)
        .build()
    return Retrofit.Builder()
        .baseUrl(ApiConfig.BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create(MockHttp.gson))
        .build()
}
