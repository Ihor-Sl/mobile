package com.example.myapplication.pw9.data.remote

import com.example.myapplication.net.MockHttp
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Імітація сервера диспетчерської (REST: GET/PUT/DELETE /tanks).
 *
 * Сервер «знає» свіжіші показники рівня палива для частини резервуарів і один новий резервуар,
 * якого ще немає в локальній БД, — так видно, як Repository об'єднує джерела.
 * Прапорець [offline] імітує втрату зв'язку (IOException), щоб показати роботу з кешем.
 */
class MockTankServer(
    now: () -> Long = System::currentTimeMillis,
    private val latencyMs: Long = 700,
) : Interceptor {

    @Volatile
    var offline: Boolean = false

    /** Кількість звернень — для перевірки кешування в тестах. */
    @Volatile
    var requestCount: Int = 0
        private set

    private val tanks = ConcurrentHashMap<String, TankDto>()

    init {
        val fresh = now() - 10 * MINUTE
        val old = now() - 3 * HOUR
        listOf(
            TankDto("TNK-01", "ДГУ-1", "Лікарня № 3", 1200.0, 1000.0, 160.0, 120.0, 38.0, old),
            // Після доливу на сервері рівень вищий і запис новіший за локальний
            TankDto("TNK-02", "ДГУ-2", "Насосна станція", 500.0, 420.0, 80.0, 55.0, 22.0, fresh),
            TankDto("TNK-03", "ДГУ-3", "ЦОД «Енергохаб»", 1000.0, 800.0, 250.0, 180.0, 60.0, old),
            // За телеметрією паливо витрачається — рівень нижчий за локальний
            TankDto("TNK-04", "ДГУ-4", "Котельня", 600.0, 60.0, 100.0, 70.0, 27.0, fresh),
            TankDto("TNK-05", "ДГУ-5", "Пожежна частина", 300.0, 260.0, 40.0, 22.0, 11.0, old),
            // Новий резервуар, якого немає в локальній БД
            TankDto("TNK-06", "ДГУ-6", "Школа № 12 (укриття)", 200.0, 150.0, 30.0, 18.0, 9.0, fresh),
        ).forEach { tanks[it.id!!] = it }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requestCount++
        MockHttp.latency(latencyMs)
        if (offline) throw IOException("Немає зв'язку з сервером диспетчерської")

        val path = MockHttp.path(request)
        if (path.firstOrNull() != "tanks") return MockHttp.error(request, 404, "not_found", "Ресурс не знайдено")
        val id = path.getOrNull(1)

        return when {
            request.method == "GET" && id == null ->
                MockHttp.json(request, 200, tanks.values.sortedBy { it.id })

            request.method == "PUT" && id != null -> {
                val dto: TankDto? = MockHttp.gson.fromJson(MockHttp.bodyOf(request), TankDto::class.java)
                if (dto == null || dto.name.isNullOrBlank()) {
                    MockHttp.error(request, 400, "bad_request", "Порожня назва")
                } else {
                    tanks[id] = dto.copy(id = id)
                    MockHttp.json(request, 200, tanks[id])
                }
            }

            request.method == "DELETE" && id != null ->
                if (tanks.remove(id) != null) MockHttp.json(request, 200, mapOf("deleted" to id))
                else MockHttp.error(request, 404, "not_found", "Резервуар $id не знайдено")

            else -> MockHttp.error(request, 405, "method_not_allowed", "Метод не підтримується")
        }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
