package com.example.myapplication.sensors

import kotlin.math.abs

/**
 * Обробка сенсорних даних без залежності від Android: перетворення сирих значень,
 * перевірка коректності, згладжування шумів і порівняння з порогом.
 */
object SensorRules {
    /** Стандартне прискорення вільного падіння, м/с². */
    const val GRAVITY = 9.80665

    /** Частка порогу, з якої значення вважається «біля порогу». */
    const val WARNING_RATIO = 0.85

    /** Коефіцієнт експоненційного згладжування (фільтр нижніх частот). */
    const val SMOOTHING = 0.3

    /**
     * Скалярне значення каналу з осей сенсора.
     * Вібрація — відхилення модуля прискорення від g: у стані спокою акселерометр
     * показує лише силу тяжіння, тому вібрація дорівнює нулю.
     */
    fun valueOf(channel: SensorChannel, x: Double, y: Double = 0.0, z: Double = 0.0): Double = when (channel) {
        SensorChannel.VIBRATION -> abs(Vec3(x, y, z).magnitude - GRAVITY)
        SensorChannel.ROTATION, SensorChannel.MAGNETIC -> Vec3(x, y, z).magnitude
        else -> x
    }

    /** Відкидає NaN, нескінченність і фізично неможливі значення (збої сенсора). */
    fun isValid(channel: SensorChannel, value: Double): Boolean =
        value.isFinite() && value in channel.validRange

    /** Експоненційне згладжування: нове = α · вимірювання + (1 − α) · попереднє. */
    fun smooth(previous: Double?, value: Double, alpha: Double = SMOOTHING): Double =
        previous?.let { alpha * value + (1 - alpha) * it } ?: value

    /**
     * Завантаження порогу у відсотках: 100 % і більше означає тривогу.
     * Для каналів «тривога нижче порогу» відношення обернене: чим темніше, тим більший відсоток.
     */
    fun loadPercent(channel: SensorChannel, value: Double, threshold: Double): Double =
        if (channel.alarmAbove) {
            value / threshold * 100
        } else {
            (threshold / value.coerceAtLeast(MIN_DIVISOR) * 100).coerceAtMost(MAX_LOAD)
        }

    fun level(channel: SensorChannel, value: Double?, threshold: Double): AlertLevel {
        if (value == null) return AlertLevel.UNKNOWN
        val load = loadPercent(channel, value, threshold)
        return when {
            load >= 100 -> AlertLevel.ALARM
            load >= WARNING_RATIO * 100 -> AlertLevel.WARNING
            else -> AlertLevel.NORMAL
        }
    }

    /** Поріг округлюється до кроку, що відповідає діапазону каналу (зручно для слайдера). */
    fun roundThreshold(channel: SensorChannel, value: Double): Double {
        val span = channel.thresholdRange.endInclusive - channel.thresholdRange.start
        val step = when {
            span <= 2 -> 0.05
            span <= 20 -> 0.1
            span <= 1000 -> 5.0
            else -> 50.0
        }
        return (Math.round(value / step) * step).coerceIn(channel.thresholdRange)
    }

    private const val MIN_DIVISOR = 0.01
    const val MAX_LOAD = 999.0
}

/** Статистика каналу, що накопичується з потоку вимірювань. */
data class ChannelStats(
    val last: Double? = null,
    val raw: Double? = null,
    val min: Double? = null,
    val max: Double? = null,
    val sum: Double = 0.0,
    val count: Int = 0,
    val rejected: Int = 0,
    val history: List<Double> = emptyList(),
    val historyAtMs: Long = 0,
) {
    val average: Double? get() = if (count == 0) null else sum / count

    /**
     * Некоректне вимірювання лише збільшує лічильник відкинутих, коректне — згладжується та враховується.
     * В історію для міні-графіка точка додається не частіше ніж раз на [HISTORY_STEP_MS],
     * тому 60 точок охоплюють ~15 с незалежно від частоти сенсора.
     */
    fun accept(channel: SensorChannel, value: Double, timeMs: Long = 0): ChannelStats {
        if (!SensorRules.isValid(channel, value)) return copy(rejected = rejected + 1)
        val smoothed = if (channel.smoothed) SensorRules.smooth(last, value) else value
        val addPoint = history.isEmpty() || timeMs == 0L || timeMs - historyAtMs >= HISTORY_STEP_MS
        return copy(
            last = smoothed,
            raw = value,
            min = min?.coerceAtMost(smoothed) ?: smoothed,
            max = max?.coerceAtLeast(smoothed) ?: smoothed,
            sum = sum + smoothed,
            count = count + 1,
            history = if (addPoint) (history + smoothed).takeLast(HISTORY_SIZE) else history,
            historyAtMs = if (addPoint) timeMs else historyAtMs,
        )
    }

    companion object {
        const val HISTORY_SIZE = 60
        const val HISTORY_STEP_MS = 250L
    }
}
