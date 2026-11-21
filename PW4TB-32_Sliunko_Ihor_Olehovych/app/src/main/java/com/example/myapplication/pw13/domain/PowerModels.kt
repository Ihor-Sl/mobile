package com.example.myapplication.pw13.domain

import java.io.IOException

/** Датчики об'єкта «Насосна станція». */
enum class SensorId(val label: String, val unit: String) {
    GRID("Напруга мережі", "В"),
    FUEL("Паливо ДГУ", "л"),
    BATTERY("Заряд АКБ", "%"),
    LOAD("Навантаження", "кВт"),
}

data class SensorSample(val id: SensorId, val value: Double, val tick: Long)

/** Тимчасовий збій зв'язку з датчиком: такий потік можна перезапустити. */
class SensorFaultException(val id: SensorId) : IOException("Немає відповіді від датчика «${id.label}»")

/** Джерело, від якого зараз живиться об'єкт. */
enum class SupplyMode(val label: String) {
    GRID("Мережа"),
    GENSET("ДГУ"),
    BATTERY("АКБ"),
    BLACKOUT("Знеструмлено"),
}

/** Стан зв'язку з окремим датчиком. */
sealed interface LinkState {
    data object Online : LinkState
    data class Retrying(val attempt: Int, val maxAttempts: Int) : LinkState
    data class Failed(val message: String) : LinkState
}

/** Узгоджений знімок, отриманий комбінуванням потоків усіх датчиків. */
data class PowerReading(
    val voltage: Double,
    val fuelLiters: Double,
    val batteryPercent: Double,
    val loadKw: Double,
    val mode: SupplyMode,
    val autonomyHours: Double,
)

/** Бізнес-логіка оцінки живлення; не залежить від Android і Flow. */
object PowerAssessor {
    const val RATED_KW = 80.0
    const val FULL_LOAD_LPH = 22.0
    const val BATTERY_KWH = 40.0
    const val DEPTH_OF_DISCHARGE = 0.8
    val NORMAL_VOLTAGE = 198.0..242.0

    /** Відкидає фізично неможливі значення (збої вимірювання). */
    fun isValid(sample: SensorSample): Boolean = when (sample.id) {
        SensorId.GRID -> sample.value in 0.0..300.0
        SensorId.FUEL -> sample.value >= 0
        SensorId.BATTERY -> sample.value in 0.0..100.0
        SensorId.LOAD -> sample.value >= 0
    }

    /** Пріоритет джерел: мережа (220 В ±10 %) -> ДГУ -> АКБ. */
    fun mode(voltage: Double, fuelLiters: Double, batteryPercent: Double): SupplyMode = when {
        voltage in NORMAL_VOLTAGE -> SupplyMode.GRID
        fuelLiters > 0 -> SupplyMode.GENSET
        batteryPercent > 0 -> SupplyMode.BATTERY
        else -> SupplyMode.BLACKOUT
    }

    /** Автономність = паливо / витрата ДГУ + корисна енергія АКБ / навантаження. */
    fun autonomyHours(fuelLiters: Double, batteryPercent: Double, loadKw: Double): Double {
        val load = loadKw.coerceAtLeast(1.0)
        val burn = FULL_LOAD_LPH * (0.3 + 0.7 * (load / RATED_KW).coerceAtMost(1.0))
        return fuelLiters / burn + BATTERY_KWH * (batteryPercent / 100) * DEPTH_OF_DISCHARGE / load
    }

    fun assess(voltage: Double, fuel: Double, battery: Double, load: Double) = PowerReading(
        voltage, fuel, battery, load, mode(voltage, fuel, battery), autonomyHours(fuel, battery, load),
    )

    /** Експоненційне згладжування — проста прогнозна модель навантаження. */
    fun smooth(previous: Double?, value: Double, alpha: Double = 0.3): Double =
        previous?.let { alpha * value + (1 - alpha) * it } ?: value

    /** Поріг автономності з поля введення: число від 1 до 72 год, кома допускається. */
    fun parseThreshold(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 1.0..72.0 }
}
