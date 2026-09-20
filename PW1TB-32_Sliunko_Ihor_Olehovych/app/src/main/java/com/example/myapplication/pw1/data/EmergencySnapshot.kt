package com.example.myapplication.pw1.data

import kotlin.random.Random

/** Джерело, від якого в поточний момент живляться відповідальні споживачі. */
enum class PowerSource(val title: String, val description: String) {
    GRID("Мережа", "Живлення від зовнішньої мережі, АКБ в резерві"),
    BATTERY("Акумулятори", "Аварія мережі — навантаження на АКБ (ДБЖ)"),
    GENERATOR("Дизель-генератор", "АКБ розряджені — працює ДГУ"),
}

/** Технічні параметри об'єкта, на яких базуються обчислення знімка. */
object EmergencySystemSpec {
    const val BATTERY_CAPACITY_KWH = 40.0
    const val BATTERY_RESERVE_PERCENT = 20
    const val GENERATOR_POWER_KW = 30
    const val GRID_MIN_VOLTAGE = 190
}

/**
 * Знімок стану системи аварійного електропостачання.
 * Похідні величини (джерело живлення, час автономії, завантаження ДГУ) обчислюються з базових.
 */
data class EmergencySnapshot(
    val gridVoltage: Int,
    val batteryPercent: Int,
    val loadKw: Int,
) {
    /** Мережа вважається наявною, якщо напруга не нижча за допустимий мінімум. */
    val isGridAvailable: Boolean get() = gridVoltage >= EmergencySystemSpec.GRID_MIN_VOLTAGE

    /** Обране джерело: мережа → АКБ (поки є запас) → ДГУ. */
    val source: PowerSource
        get() = when {
            isGridAvailable -> PowerSource.GRID
            batteryPercent > EmergencySystemSpec.BATTERY_RESERVE_PERCENT + 10 -> PowerSource.BATTERY
            else -> PowerSource.GENERATOR
        }

    /** Скільки хвилин АКБ можуть тримати поточне навантаження до досягнення резерву. */
    val autonomyMinutes: Int
        get() {
            val usablePercent = (batteryPercent - EmergencySystemSpec.BATTERY_RESERVE_PERCENT).coerceAtLeast(0)
            val usableKwh = EmergencySystemSpec.BATTERY_CAPACITY_KWH * usablePercent / 100.0
            return if (loadKw <= 0) 0 else (usableKwh / loadKw * 60).toInt()
        }

    /** Завантаження ДГУ у відсотках (0, якщо генератор не працює). */
    val generatorLoadPercent: Int
        get() = if (source == PowerSource.GENERATOR) {
            (loadKw * 100 / EmergencySystemSpec.GENERATOR_POWER_KW).coerceIn(0, 100)
        } else 0
}

/** Імітація телеметрії: випадково генерує наступний стан системи. */
object EmergencySimulator {
    val initial = EmergencySnapshot(gridVoltage = 230, batteryPercent = 87, loadKw = 12)

    /** Ймовірність аварії мережі при кожному оновленні. */
    private const val OUTAGE_PROBABILITY = 0.4

    fun next(random: Random = Random.Default): EmergencySnapshot {
        val outage = random.nextDouble() < OUTAGE_PROBABILITY
        return EmergencySnapshot(
            gridVoltage = if (outage) random.nextInt(0, 120) else random.nextInt(215, 241),
            batteryPercent = random.nextInt(8, 101),
            loadKw = random.nextInt(5, 26),
        )
    }
}
