package com.example.myapplication.pw8.data.remote

import com.example.myapplication.pw8.data.local.EventType
import com.example.myapplication.pw8.data.local.SourceKind
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.delay

/** Подія у форматі віддаленої системи SCADA. */
data class RemoteEvent(
    val remoteId: String,
    val sourceName: String,
    val sourceKind: SourceKind,
    val sourceRatedPowerKw: Double,
    val type: EventType,
    val startedAt: Long,
    val durationMin: Int,
    val avgLoadKw: Double,
    val fuelUsedL: Double,
    val note: String,
)

/** Віддалене джерело даних (мережа). */
interface RemoteEventDataSource {
    suspend fun fetchRecentEvents(): List<RemoteEvent>
}

/**
 * Імітація сервера SCADA: затримка мережі та випадкові збої зв'язку,
 * щоб репозиторій міг продемонструвати обробку помилок.
 */
class FakeScadaDataSource(
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val latencyMs: Long = 1_200,
    private val failureProbability: Double = 0.25,
) : RemoteEventDataSource {

    override suspend fun fetchRecentEvents(): List<RemoteEvent> {
        delay(latencyMs)
        if (random.nextDouble() < failureProbability) throw IOException("Сервер SCADA недоступний")
        val hour = 3_600_000L
        val t = now()
        return listOf(
            RemoteEvent("SCADA-1001", "ДГУ-1 Лікарня", SourceKind.GENERATOR, 160.0, EventType.OUTAGE,
                t - 30 * hour, 95, 104.0, 41.0, "Відключення фідера 10 кВ, автоматичний пуск ДГУ"),
            RemoteEvent("SCADA-1002", "ДБЖ ЦОД", SourceKind.UPS, 200.0, EventType.OUTAGE,
                t - 30 * hour, 4, 150.0, 0.0, "ДБЖ утримував навантаження до пуску ДГУ"),
            RemoteEvent("SCADA-1003", "ДГУ-3 Пожежна частина", SourceKind.GENERATOR, 40.0, EventType.TEST,
                t - 52 * hour, 20, 22.0, 3.1, "Плановий щотижневий пуск"),
            RemoteEvent("SCADA-1004", "ДГУ-2 Насосна", SourceKind.GENERATOR, 80.0, EventType.OUTAGE,
                t - 75 * hour, 210, 61.0, 58.0, "Аварійне відключення ТП-17"),
        )
    }
}
