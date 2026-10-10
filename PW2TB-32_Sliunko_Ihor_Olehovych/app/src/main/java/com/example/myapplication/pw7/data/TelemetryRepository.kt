package com.example.myapplication.pw7.data

import com.example.myapplication.pw7.domain.Telemetry
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Джерело даних (Model у MVVM). ViewModel залежить лише від цього інтерфейсу,
 * тому реальний обмін із контролером (Modbus/HTTP) можна підставити без змін у ViewModel та UI.
 */
interface TelemetryRepository {
    /** Читає поточну телеметрію; при втраті зв'язку кидає [IOException]. */
    suspend fun fetchTelemetry(): Telemetry

    /** Одна спроба пуску ДГУ; true — двигун запустився. */
    suspend fun tryStartGenerator(): Boolean
}

/**
 * Імітація контролера: затримка моделює мережевий запит, а з імовірністю [failureProbability]
 * зв'язок «обривається» — так у UI можна побачити стан помилки та повторити запит.
 */
class FakeTelemetryRepository(
    private val random: Random = Random.Default,
    private val latencyMs: Long = 900,
    private val failureProbability: Double = 0.15,
    private val startFailureProbability: Double = 0.35,
) : TelemetryRepository {

    override suspend fun fetchTelemetry(): Telemetry {
        delay(latencyMs)
        if (random.nextDouble() < failureProbability) {
            throw IOException("Немає зв'язку з контролером ДБЖ")
        }
        val outage = random.nextDouble() < 0.2
        return Telemetry(
            gridVoltage = if (outage) random.nextDouble(0.0, 150.0) else random.nextDouble(200.0, 255.0),
            gridFrequencyHz = random.nextDouble(49.5, 50.6),
            batteryPercent = random.nextInt(15, 101),
            batteryTempC = random.nextDouble(20.0, 39.0),
            upsLoadPercent = random.nextInt(30, 96),
            fuelPercent = random.nextInt(5, 101),
            generatorRunning = outage && random.nextBoolean(),
        )
    }

    override suspend fun tryStartGenerator(): Boolean {
        delay(latencyMs / 3)
        return random.nextDouble() >= startFailureProbability
    }
}
