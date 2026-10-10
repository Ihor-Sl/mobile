package com.example.myapplication.pw8.domain

import com.example.myapplication.pw8.data.local.EventType
import com.example.myapplication.pw8.data.local.EventWithSource

/** Варіанти сортування журналу; назва елемента передається в SQL-запит DAO. */
enum class EventSort(val title: String) {
    DATE_DESC("Спочатку нові"),
    DATE_ASC("Спочатку старі"),
    DURATION_DESC("Найдовші"),
    LOAD_DESC("Найбільше навантаження"),
}

/** Поточні параметри фільтрації та сортування (null — без фільтра). */
data class EventFilter(
    val type: EventType? = null,
    val sourceId: Long? = null,
    val query: String = "",
    val sort: EventSort = EventSort.DATE_DESC,
)

/** Підсумок по відфільтрованому списку подій. */
data class LogSummary(
    val count: Int = 0,
    val totalHours: Double = 0.0,
    val totalEnergyKwh: Double = 0.0,
    val totalFuelL: Double = 0.0,
) {
    companion object {
        fun of(events: List<EventWithSource>) = LogSummary(
            count = events.size,
            totalHours = events.sumOf { it.event.durationMin } / 60.0,
            totalEnergyKwh = events.sumOf { it.energyKwh },
            totalFuelL = events.sumOf { it.event.fuelUsedL },
        )
    }
}
