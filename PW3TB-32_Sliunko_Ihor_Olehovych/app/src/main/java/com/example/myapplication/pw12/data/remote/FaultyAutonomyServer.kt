package com.example.myapplication.pw12.data.remote

import com.example.myapplication.net.MockHttp
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Сценарії поведінки сервера. Тимчасові збої детерміновані (наприклад, «503 двічі, потім 200»),
 * тому роботу retry-логіки можна відтворити й перевірити тестами.
 */
enum class FaultScenario(val label: String) {
    NORMAL("Норма (200)"),
    TRANSIENT_503("503 двічі, потім успіх"),
    TIMEOUT_ONCE("Таймаут, потім успіх"),
    RATE_LIMIT_429("429 з Retry-After, потім успіх"),
    SERVER_500("500 постійно"),
    NOT_FOUND_404("404 Не знайдено"),
    UNAUTHORIZED_401("401 Токен прострочено"),
    BAD_JSON("Некоректний JSON"),
}

/** Імітаційний сервер прогнозу автономності з керованими збоями. */
class FaultyAutonomyServer(
    private val latencyMs: Long = 600,
    private val timeoutMs: Long = 1_500,
) : Interceptor {

    private val counter = AtomicInteger()

    /** Зміна сценарію скидає лічильник запитів. */
    @Volatile
    var scenario: FaultScenario = FaultScenario.NORMAL
        set(value) {
            field = value
            counter.set(0)
        }

    private data class Site(val name: String, val fuelL: Double, val lph: Double)

    private val sites = mapOf(
        "OBJ-01" to Site("Лікарня № 3", 870.0, 31.35),
        "OBJ-02" to Site("Насосна станція «Північна»", 180.0, 17.19),
        "OBJ-03" to Site("ЦОД «Енергохаб»", 640.0, 54.96),
        "OBJ-04" to Site("Котельня № 7", 120.0, 21.33),
        "OBJ-05" to Site("Пожежна частина № 12", 250.0, 9.08),
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val n = counter.incrementAndGet()
        MockHttp.latency(latencyMs)

        when (scenario) {
            FaultScenario.NORMAL -> Unit
            FaultScenario.TRANSIENT_503 -> if (n % 3 != 0) {
                return MockHttp.error(request, 503, "unavailable", "Сервіс прогнозів перезапускається")
            }
            FaultScenario.TIMEOUT_ONCE -> if (n % 2 == 1) {
                MockHttp.latency(timeoutMs)
                throw SocketTimeoutException("timeout: відповідь не отримано за ${(latencyMs + timeoutMs) / 1000.0} с")
            }
            FaultScenario.RATE_LIMIT_429 -> if (n % 2 == 1) {
                return MockHttp.error(request, 429, "rate_limited", "Перевищено ліміт запитів", mapOf("Retry-After" to "2"))
            }
            FaultScenario.SERVER_500 -> return MockHttp.error(request, 500, "internal", "NullPointerException у модулі розрахунку")
            FaultScenario.NOT_FOUND_404 -> return MockHttp.error(request, 404, "not_found", "Ресурс /autonomy тимчасово вимкнено")
            FaultScenario.UNAUTHORIZED_401 -> return MockHttp.error(request, 401, "token_expired", "Термін дії токена минув")
            // Поле points має бути масивом — Gson не зможе розібрати відповідь
            FaultScenario.BAD_JSON -> return MockHttp.json(request, 200, """{"facility_code":"OBJ-01","autonomy_h":"багато","points":"не масив"}""")
        }

        val path = MockHttp.path(request)
        val code = path.getOrNull(1)
        val hours = request.url.queryParameter("hours")?.toIntOrNull()
            ?: return MockHttp.error(request, 400, "bad_request", "Параметр hours обов'язковий")
        val site = sites[code] ?: return MockHttp.error(request, 404, "not_found", "Об'єкт $code не зареєстровано")

        val points = (0..hours).map { h -> ForecastPointDto(h, (site.fuelL - site.lph * h).coerceAtLeast(0.0)) }
        return MockHttp.json(request, 200, AutonomyDto(code, site.name, hours, site.fuelL / site.lph, site.lph, points))
    }
}
