package com.example.myapplication.pw20.domain

import com.example.myapplication.sensors.SensorChannel
import com.example.myapplication.sensors.SensorRules
import com.example.myapplication.sensors.SensorSample
import com.example.myapplication.sensors.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt

/** Нахил рами ДГУ: крен (вліво/вправо), тангаж (вперед/назад) і загальний кут від горизонталі, градуси. */
data class Tilt(val rollDeg: Double, val pitchDeg: Double, val totalDeg: Double)

/** Знімок руху для компонентів ПЗ 20. */
data class MotionState(
    val accel: Vec3 = Vec3(0.0, 0.0, SensorRules.GRAVITY),
    val gravity: Vec3 = Vec3(0.0, 0.0, SensorRules.GRAVITY),
    val gyro: Vec3 = Vec3.ZERO,
    val accelTrail: List<Vec3> = emptyList(),
    val gyroTrail: List<Vec3> = emptyList(),
    val accelHistory: List<Vec3> = emptyList(),
    val gyroHistory: List<Vec3> = emptyList(),
    val samples: Int = 0,
    val rejected: Int = 0,
) {
    /** Вібрація — модуль лінійного прискорення (без складової сили тяжіння). */
    val vibration: Double get() = MotionMath.vibration(accel, gravity)
    val rotation: Double get() = gyro.magnitude
}

object MotionMath {
    /** Коефіцієнт фільтра нижніх частот для виділення сили тяжіння. */
    const val GRAVITY_FILTER = 0.8
    const val TRAIL_SIZE = 24
    const val HISTORY_SIZE = 120

    /** Фільтр нижніх частот: повільна складова прискорення — це сила тяжіння (нахил). */
    fun lowPass(previous: Vec3, value: Vec3, alpha: Double = GRAVITY_FILTER) = Vec3(
        alpha * previous.x + (1 - alpha) * value.x,
        alpha * previous.y + (1 - alpha) * value.y,
        alpha * previous.z + (1 - alpha) * value.z,
    )

    fun vibration(accel: Vec3, gravity: Vec3): Double = (accel - gravity).magnitude

    /** Кути з вектора сили тяжіння; [zero] — вектор, збережений під час калібрування нуля. */
    fun tilt(gravity: Vec3, zero: Vec3 = Vec3.ZERO): Tilt {
        val x = gravity.x - zero.x
        val y = gravity.y - zero.y
        val z = gravity.z
        return Tilt(
            rollDeg = Math.toDegrees(atan2(x, z)),
            pitchDeg = Math.toDegrees(atan2(y, z)),
            totalDeg = Math.toDegrees(atan2(sqrt(x * x + y * y), z)),
        )
    }

    /**
     * Перетворення проєкції вектора XY у координати компонента в межах [-1; 1]:
     * [fullScale] одиниць відповідає краю шкали при масштабі 1, довжина обмежується колом.
     */
    fun toScope(x: Double, y: Double, fullScale: Double, zoom: Double): Pair<Double, Double> {
        var nx = x / fullScale * zoom
        var ny = y / fullScale * zoom
        val length = sqrt(nx * nx + ny * ny)
        if (length > 1) {
            nx /= length
            ny /= length
        }
        return nx to ny
    }

    /** Додає вимірювання акселерометра або гіроскопа; некоректні (NaN, без осей) відкидаються. */
    fun MotionState.reduce(sample: SensorSample): MotionState {
        val vector = sample.vector
        if (vector == null || !vector.isFinite()) return copy(rejected = rejected + 1)
        return when (sample.channel) {
            SensorChannel.VIBRATION -> copy(
                accel = vector,
                gravity = if (samples == 0) vector else lowPass(gravity, vector),
                accelTrail = (accelTrail + vector).takeLast(TRAIL_SIZE),
                accelHistory = (accelHistory + vector).takeLast(HISTORY_SIZE),
                samples = samples + 1,
            )
            SensorChannel.ROTATION -> copy(
                gyro = vector,
                gyroTrail = (gyroTrail + vector).takeLast(TRAIL_SIZE),
                gyroHistory = (gyroHistory + vector).takeLast(HISTORY_SIZE),
                samples = samples + 1,
            )
            else -> this
        }
    }
}
