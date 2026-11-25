package com.example.myapplication.sensors

import android.hardware.Sensor

/** Тип каналу для фільтрації на панелі ПЗ 17. */
enum class SensorGroup(val label: String) {
    MOTION("Рух"),
    ENVIRONMENT("Середовище"),
    ELECTRICAL("Електрика"),
}

/**
 * Канал вимірювання: сенсор телефона, який моделює датчик об'єкта аварійного живлення.
 *
 * [validRange] — фізично можливі значення: усе поза ним вважається збоєм вимірювання.
 * [alarmAbove] — напрямок порогу: true — тривога, коли значення перевищує поріг (вібрація,
 * температура), false — коли падає нижче (освітленість, напруга АКБ).
 * [sensorType] — тип апаратного сенсора Android; null для параметрів батареї.
 * [smoothed] — чи згладжувати значення: сенсори з режимом on-change (світло, температура)
 * надсилають подію лише при зміні, тож згладжене значення «застрягло» б між подіями.
 */
enum class SensorChannel(
    val label: String,
    val source: String,
    val unit: String,
    val group: SensorGroup,
    val sensorType: Int?,
    val validRange: ClosedFloatingPointRange<Double>,
    val defaultThreshold: Double,
    val thresholdRange: ClosedFloatingPointRange<Double>,
    val alarmAbove: Boolean,
    val smoothed: Boolean = true,
) {
    VIBRATION("Вібрація ДГУ", "Акселерометр", "м/с²", SensorGroup.MOTION, Sensor.TYPE_ACCELEROMETER, 0.0..160.0, 2.0, 0.5..10.0, true),
    ROTATION("Коливання рами", "Гіроскоп", "рад/с", SensorGroup.MOTION, Sensor.TYPE_GYROSCOPE, 0.0..35.0, 1.0, 0.1..5.0, true),
    MAGNETIC("Поле силового кабелю", "Магнітометр", "мкТл", SensorGroup.ENVIRONMENT, Sensor.TYPE_MAGNETIC_FIELD, 0.0..2000.0, 80.0, 20.0..400.0, true),
    LIGHT("Аварійне освітлення", "Датчик світла", "лк", SensorGroup.ENVIRONMENT, Sensor.TYPE_LIGHT, 0.0..120_000.0, 50.0, 5.0..500.0, false, smoothed = false),
    ROOM_TEMP("Температура приміщення", "Термометр", "°C", SensorGroup.ENVIRONMENT, Sensor.TYPE_AMBIENT_TEMPERATURE, -40.0..85.0, 35.0, 15.0..60.0, true, smoothed = false),
    BATTERY_TEMP("Температура АКБ", "Батарея телефона", "°C", SensorGroup.ELECTRICAL, null, -20.0..90.0, 40.0, 20.0..60.0, true),
    BATTERY_VOLTAGE("Напруга АКБ", "Батарея телефона", "В", SensorGroup.ELECTRICAL, null, 0.5..10.0, 3.6, 3.0..4.2, false),
    BATTERY_CURRENT("Струм АКБ", "Батарея телефона", "мА", SensorGroup.ELECTRICAL, null, 0.0..10_000.0, 1500.0, 100.0..5000.0, true),
}

/** Стан значення відносно порогу. */
enum class AlertLevel(val label: String) {
    UNKNOWN("Немає даних"),
    NORMAL("Норма"),
    WARNING("Біля порогу"),
    ALARM("Тривога"),
}

/** Тривимірний вектор вимірювання (м/с², рад/с або мкТл). */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    val magnitude: Double get() = kotlin.math.sqrt(x * x + y * y + z * z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}

/** Одне вимірювання каналу: скалярне значення та (для сенсорів руху) вихідний вектор. */
data class SensorSample(
    val channel: SensorChannel,
    val value: Double,
    val vector: Vec3? = null,
    val timeMs: Long = 0,
)
