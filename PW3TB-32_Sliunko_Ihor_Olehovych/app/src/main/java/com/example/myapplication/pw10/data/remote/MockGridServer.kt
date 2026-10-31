package com.example.myapplication.pw10.data.remote

import com.example.myapplication.net.MockHttp
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Interceptor
import okhttp3.Response

/** Якість зв'язку, яку імітує сервер. */
enum class NetworkProfile(val label: String) {
    STABLE("Стабільна"),
    /** Кожна непарна спроба запиту до ресурсу повертає 503 — демонструє повторні спроби. */
    UNSTABLE("Нестабільна"),
    /** Мережі немає (IOException) — демонструє офлайн-кеш. */
    OFFLINE("Немає мережі"),
}

/**
 * Імітація REST-сервера обленерго / диспетчерської: три ресурси з вкладеними JSON-структурами.
 * Відповіді будуються як текст JSON, тобто Gson на клієнті розбирає їх так само, як справжні.
 * Один запис навмисно некоректний (порожня назва, від'ємне навантаження) — для перевірки валідації.
 */
class MockGridServer(
    private val now: () -> Long = System::currentTimeMillis,
    private val latencyMs: Long = 400,
) : Interceptor {

    /** Зміна профілю скидає лічильники, щоб сценарій відтворювався з початку. */
    @Volatile
    var profile: NetworkProfile = NetworkProfile.STABLE
        set(value) {
            field = value
            attempts.clear()
        }

    /** Кількість звернень до кожного ресурсу (для UNSTABLE і для тестів кешування). */
    private val attempts = ConcurrentHashMap<String, AtomicInteger>()
    val totalRequests = AtomicInteger()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        totalRequests.incrementAndGet()
        MockHttp.latency(latencyMs)
        val path = MockHttp.path(request)
        val key = path.joinToString("/")
        val attempt = attempts.getOrPut(key) { AtomicInteger() }.incrementAndGet()

        when (profile) {
            NetworkProfile.OFFLINE -> throw IOException("Немає з'єднання з сервером")
            NetworkProfile.UNSTABLE -> if (attempt % 2 == 1) {
                return MockHttp.error(request, 503, "unavailable", "Сервер перевантажений, спробуйте пізніше")
            }
            NetworkProfile.STABLE -> Unit
        }

        return when {
            path == listOf("facilities") -> MockHttp.json(request, 200, FACILITIES_JSON)
            path == listOf("outages", "schedule") -> {
                val date = request.url.queryParameter("date")
                    ?: return MockHttp.error(request, 400, "bad_request", "Параметр date обов'язковий")
                MockHttp.json(request, 200, SCHEDULE_JSON.replace("{date}", date))
            }
            path.size == 3 && path[0] == "facilities" && path[2] == "telemetry" -> {
                val id = path[1].toIntOrNull()
                val t = TELEMETRY[id] ?: return MockHttp.error(request, 404, "not_found", "Об'єкт ${path[1]} не знайдено")
                MockHttp.json(request, 200, TelemetryDto(id, now() - 60_000, t.first, t.second, GridStateDto(t.third, if (t.third) 229.0 else 0.0)))
            }
            else -> MockHttp.error(request, 404, "not_found", "Ресурс не знайдено")
        }
    }

    private companion object {
        /** Рівень палива, заряд АКБ, наявність мережі. */
        val TELEMETRY = mapOf(
            1 to Triple(870.0, 0.95, true),
            2 to Triple(180.0, 0.6, false),
            3 to Triple(640.0, 1.0, true),
            4 to Triple(120.0, null, true),
            5 to Triple(250.0, 0.9, true),
        )

        val FACILITIES_JSON = """
        {
          "updated_at": "2026-10-29T07:00:00Z",
          "facilities": [
            { "id": 1, "name": "Лікарня № 3", "category": 1,
              "address": { "city": "Київ", "street": "вул. Енергетиків, 3" },
              "load": { "total_kw": 120, "critical_kw": 70 },
              "sources": [
                { "type": "DGU", "rated_kw": 160, "tank_l": 1200, "full_load_lph": 38 },
                { "type": "UPS", "rated_kw": 100, "battery_kwh": 150 } ] },
            { "id": 2, "name": "Насосна станція «Північна»", "category": 1,
              "address": { "city": "Київ", "street": "вул. Водопровідна, 12" },
              "load": { "total_kw": 90, "critical_kw": 55 },
              "sources": [
                { "type": "DGU", "rated_kw": 80, "tank_l": 500, "full_load_lph": 22 },
                { "type": "UPS", "rated_kw": 30, "battery_kwh": 40 } ] },
            { "id": 3, "name": "ЦОД «Енергохаб»", "category": 1,
              "address": { "city": "Бровари", "street": "вул. Цифрова, 1" },
              "load": { "total_kw": 220, "critical_kw": 180 },
              "sources": [
                { "type": "DGU", "rated_kw": 250, "tank_l": 1000, "full_load_lph": 60 },
                { "type": "UPS", "rated_kw": 200, "battery_kwh": 400 } ] },
            { "id": 4, "name": "Котельня № 7", "category": 2,
              "address": { "city": "Київ", "street": "вул. Теплова, 7" },
              "load": { "total_kw": 70, "critical_kw": 45 },
              "sources": [ { "type": "DGU", "rated_kw": 100, "tank_l": 600, "full_load_lph": 27 } ] },
            { "id": 5, "name": "Пожежна частина № 12", "category": 2,
              "address": { "city": "Ірпінь", "street": "вул. Рятувальників, 12" },
              "load": { "total_kw": 30, "critical_kw": 20 },
              "sources": [
                { "type": "DGU", "rated_kw": 40, "tank_l": 300, "full_load_lph": 11 },
                { "type": "UPS", "rated_kw": 10, "battery_kwh": 15 } ] },
            { "id": 6, "name": "", "category": 3,
              "load": { "total_kw": -5 },
              "sources": [] }
          ]
        }
        """.trimIndent()

        val SCHEDULE_JSON = """
        {
          "date": "{date}",
          "queues": [
            { "queue": "1.1", "facility_ids": [1, 4], "windows": [ { "from_h": 8, "to_h": 12 }, { "from_h": 18, "to_h": 22 } ] },
            { "queue": "2.2", "facility_ids": [2, 5], "windows": [ { "from_h": 6, "to_h": 10 }, { "from_h": 14, "to_h": 18 }, { "from_h": 22, "to_h": 24 } ] },
            { "queue": "3.1", "facility_ids": [3], "windows": [ { "from_h": 10, "to_h": 16 }, { "from_h": 20, "to_h": 24 } ] }
          ]
        }
        """.trimIndent()
    }
}
