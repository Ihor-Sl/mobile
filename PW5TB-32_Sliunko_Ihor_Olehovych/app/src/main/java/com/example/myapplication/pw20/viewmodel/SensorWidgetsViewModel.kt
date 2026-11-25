package com.example.myapplication.pw20.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.pw20.domain.MotionMath
import com.example.myapplication.pw20.domain.MotionMath.reduce
import com.example.myapplication.pw20.domain.MotionState
import com.example.myapplication.pw20.domain.Tilt
import com.example.myapplication.pw20.ui.components.WidgetStyle
import com.example.myapplication.sensors.SensorChannel
import com.example.myapplication.sensors.SensorSource
import com.example.myapplication.sensors.Vec3
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class SensorWidgetsUiState(
    val motion: MotionState = MotionState(),
    val tilt: Tilt = Tilt(0.0, 0.0, 0.0),
    val calibrated: Boolean = false,
    val paused: Boolean = false,
    val accelAvailable: Boolean = true,
    val gyroAvailable: Boolean = true,
)

/**
 * ViewModel ПЗ 20: акселерометр і гіроскоп об'єднуються в один стан руху (scan),
 * а sample обмежує оновлення UI до ~20 кадрів/с — плавність забезпечує анімація компонентів.
 * Налаштування вигляду ([style]) — окремий StateFlow, тому їх зміна не перезапускає сенсори.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SensorWidgetsViewModel(
    source: SensorSource,
    frameMs: Long = FRAME_MS,
) : ViewModel() {
    private val accelAvailable = source.isAvailable(SensorChannel.VIBRATION)
    private val gyroAvailable = source.isAvailable(SensorChannel.ROTATION)
    private val paused = MutableStateFlow(false)
    private val zero = MutableStateFlow<Vec3?>(null)

    private val _style = MutableStateFlow(WidgetStyle())
    val style: StateFlow<WidgetStyle> = _style.asStateFlow()

    /** Пауза відписує слухачів сенсорів (flatMapLatest), накопичений стан зберігається в scan. */
    private val motion: Flow<MotionState> = paused
        .flatMapLatest { stopped ->
            if (stopped) emptyFlow() else merge(source.samples(SensorChannel.VIBRATION), source.samples(SensorChannel.ROTATION))
        }
        .scan(MotionState()) { state, sample -> state.reduce(sample) }
        .sample(frameMs)
        .onStart { emit(MotionState()) }

    val uiState: StateFlow<SensorWidgetsUiState> = combine(motion, zero, paused) { state, offset, stopped ->
        SensorWidgetsUiState(
            motion = state,
            tilt = MotionMath.tilt(state.gravity, offset ?: Vec3.ZERO),
            calibrated = offset != null,
            paused = stopped,
            accelAvailable = accelAvailable,
            gyroAvailable = gyroAvailable,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SensorWidgetsUiState(accelAvailable = accelAvailable, gyroAvailable = gyroAvailable))

    fun onStyleChange(value: WidgetStyle) {
        _style.value = value.copy(
            scale = value.scale.coerceIn(WidgetStyle.SCALE_RANGE),
            strokeDp = value.strokeDp.coerceIn(WidgetStyle.STROKE_RANGE),
        )
    }

    /** Поточне положення рами приймається за нуль (рама встановлена на нерівній підлозі). */
    fun calibrate() { zero.value = uiState.value.motion.gravity.let { Vec3(it.x, it.y, 0.0) } }

    fun resetCalibration() { zero.value = null }

    fun togglePause() = paused.update { !it }

    companion object {
        const val FRAME_MS = 50L
        const val TILT_LIMIT_DEG = 5f
    }
}
