package com.example.myapplication.sensors

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

/** Джерело вимірювань; інтерфейс дозволяє підмінити сенсори телефона в модульних тестах. */
interface SensorSource {
    fun isAvailable(channel: SensorChannel): Boolean
    fun samples(channel: SensorChannel): Flow<SensorSample>
}

/**
 * Сенсори телефона через SensorManager та параметри батареї через BatteryManager.
 *
 * Кожен потік «холодний»: слухач реєструється лише під час збирання потоку,
 * а awaitClose знімає реєстрацію, коли екран більше не потребує даних (економія заряду).
 */
class AndroidSensorSource(context: Context) : SensorSource {
    private val app = context.applicationContext
    private val sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val batteryManager = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    override fun isAvailable(channel: SensorChannel): Boolean =
        channel.sensorType?.let { sensorManager.getDefaultSensor(it) != null } ?: true

    override fun samples(channel: SensorChannel): Flow<SensorSample> =
        if (channel.sensorType != null) hardware(channel, channel.sensorType) else battery(channel)

    private fun hardware(channel: SensorChannel, type: Int): Flow<SensorSample> {
        val sensor = sensorManager.getDefaultSensor(type) ?: return emptyFlow()
        return callbackFlow {
            val listener = object : SensorEventListener {
                // Викликається системою з кожним новим вимірюванням сенсора
                override fun onSensorChanged(event: SensorEvent) {
                    val v = event.values
                    val x = v[0].toDouble()
                    val y = v.getOrElse(1) { 0f }.toDouble()
                    val z = v.getOrElse(2) { 0f }.toDouble()
                    val vector = if (v.size >= 3) Vec3(x, y, z) else null
                    trySend(SensorSample(channel, SensorRules.valueOf(channel, x, y, z), vector, System.currentTimeMillis()))
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            // SENSOR_DELAY_UI (~16 Гц) достатньо для інтерфейсу та не розряджає батарею
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            awaitClose { sensorManager.unregisterListener(listener) }
        }.conflate() // Повільний споживач отримує лише найсвіжіше значення
    }

    /**
     * Параметри батареї змінюються повільно, тому зчитуються раз на секунду:
     * температура й напруга — зі sticky-broadcast ACTION_BATTERY_CHANGED, струм — з BatteryManager.
     */
    private fun battery(channel: SensorChannel): Flow<SensorSample> = flow {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        while (true) {
            val status = ContextCompat.registerReceiver(app, null, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            val value = when (channel) {
                SensorChannel.BATTERY_TEMP -> status?.intExtra(BatteryManager.EXTRA_TEMPERATURE)?.div(10.0)
                SensorChannel.BATTERY_VOLTAGE -> status?.intExtra(BatteryManager.EXTRA_VOLTAGE)?.div(1000.0)
                // Мікроампери -> міліампери; Integer.MIN_VALUE означає, що пристрій не підтримує вимірювання
                else -> batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                    .takeIf { it != Int.MIN_VALUE }?.let { kotlin.math.abs(it / 1000.0) }
            }
            emit(SensorSample(channel, value ?: Double.NaN, null, System.currentTimeMillis()))
            delay(BATTERY_PERIOD_MS)
        }
    }

    private fun Intent.intExtra(name: String): Int? = getIntExtra(name, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }

    private companion object {
        const val BATTERY_PERIOD_MS = 1_000L
    }
}
