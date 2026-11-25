package com.example.myapplication.pw20.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.pw20.ui.components.ArcGauge
import com.example.myapplication.pw20.ui.components.AxisTrace
import com.example.myapplication.pw20.ui.components.MarkerStyle
import com.example.myapplication.pw20.ui.components.TiltLevel
import com.example.myapplication.pw20.ui.components.VectorScope
import com.example.myapplication.pw20.ui.components.WidgetPalette
import com.example.myapplication.pw20.ui.components.WidgetStyle
import com.example.myapplication.pw20.viewmodel.SensorWidgetsUiState
import com.example.myapplication.pw20.viewmodel.SensorWidgetsViewModel
import com.example.myapplication.sensors.AndroidSensorSource
import com.example.myapplication.sensors.SensorChannel
import com.example.myapplication.sensors.SensorRules
import com.example.myapplication.sensors.Vec3
import com.example.myapplication.ui.common.InfoRow
import com.example.myapplication.ui.common.MessageBanner
import com.example.myapplication.ui.common.ScreenTitle
import com.example.myapplication.ui.common.SectionCard
import com.example.myapplication.ui.common.Tone
import com.example.myapplication.ui.common.fmt

/** Повна шкала компаса при масштабі 1: прискорення g і кутова швидкість 2 рад/с. */
private const val GYRO_FULL_SCALE = 2.0

/** Екран ПЗ 20: користувацькі компоненти для контролю нахилу та вібрації рами ДГУ. */
@Composable
fun SensorWidgetsScreen() {
    val context = LocalContext.current.applicationContext
    val viewModel: SensorWidgetsViewModel = viewModel { SensorWidgetsViewModel(AndroidSensorSource(context)) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val style by viewModel.style.collectAsStateWithLifecycle()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widgets: @Composable () -> Unit = { WidgetsPanel(state, style) }
        val settings: @Composable () -> Unit = { SettingsPanel(state, style, viewModel) }
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScrollColumn(Modifier.weight(1f)) { widgets() }
                ScrollColumn(Modifier.weight(1f)) { settings() }
            }
        } else {
            ScrollColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                widgets()
                settings()
            }
        }
    }
}

@Composable
private fun ScrollColumn(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun WidgetsPanel(state: SensorWidgetsUiState, style: WidgetStyle) {
    ScreenTitle("Рама ДГУ", "Власні Compose-компоненти для акселерометра та гіроскопа")
    if (!state.accelAvailable || !state.gyroAvailable) {
        MessageBanner("На пристрої немає ${if (!state.accelAvailable) "акселерометра" else "гіроскопа"}: відповідний компонент не оновлюється", Tone.WARN)
    }
    val tilt = state.tilt
    if (tilt.totalDeg > SensorWidgetsViewModel.TILT_LIMIT_DEG) {
        MessageBanner("Нахил рами ${tilt.totalDeg.fmt(1)}° перевищує допустимі ${SensorWidgetsViewModel.TILT_LIMIT_DEG.toDouble().fmt(0)}°", Tone.BAD)
    }
    val motion = state.motion
    SectionCard(title = "Векторний компас: прискорення та кутова швидкість") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            VectorScope(
                accel = motion.accel,
                gyro = motion.gyro,
                accelTrail = motion.accelTrail,
                gyroTrail = motion.gyroTrail,
                accelFullScale = SensorRules.GRAVITY,
                gyroFullScale = GYRO_FULL_SCALE,
                style = style,
                modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(),
            )
        }
        Legend("Акселерометр", motion.accel, "м/с²", style.palette.accel, style.showAccel)
        Legend("Гіроскоп", motion.gyro, "рад/с", style.palette.gyro, style.showGyro)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionCard(Modifier.weight(1f), title = "Рівень рами") {
            TiltLevel(tilt, SensorWidgetsViewModel.TILT_LIMIT_DEG, style, Modifier.fillMaxWidth())
            Text(
                "Крен ${tilt.rollDeg.fmt(1)}° · тангаж ${tilt.pitchDeg.fmt(1)}°",
                style = MaterialTheme.typography.labelMedium,
            )
            Text("Загальний нахил ${tilt.totalDeg.fmt(1)}°", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
        SectionCard(Modifier.weight(1f), title = "Вібрація ДГУ") {
            val limit = SensorChannel.VIBRATION.defaultThreshold
            ArcGauge(motion.vibration, limit * 2, limit * SensorRules.WARNING_RATIO, limit, "поріг ${limit.fmt(0)}", "м/с²", style, Modifier.fillMaxWidth())
            Text("|a − g| без сили тяжіння", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    SectionCard(title = "Осі сенсорів у реальному часі") {
        val limit = SensorChannel.ROTATION.defaultThreshold
        ArcGauge(
            motion.rotation, limit * 2, limit * SensorRules.WARNING_RATIO, limit, "коливання рами", "рад/с", style,
            Modifier.widthIn(max = 220.dp).align(Alignment.CenterHorizontally),
        )
        AxisTrace(motion.accelHistory, SensorRules.GRAVITY, "Акселерометр, ±${(SensorRules.GRAVITY / style.scale).fmt(1)} м/с²", style)
        AxisTrace(motion.gyroHistory, GYRO_FULL_SCALE, "Гіроскоп, ±${(GYRO_FULL_SCALE / style.scale).fmt(2)} рад/с", style)
    }
}

@Composable
private fun Legend(name: String, v: Vec3, unit: String, color: androidx.compose.ui.graphics.Color, visible: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("●  ", color = if (visible) color else MaterialTheme.colorScheme.outline)
        Text(
            "$name: (${v.x.fmt(2)}; ${v.y.fmt(2)}; ${v.z.fmt(2)}) $unit, |v| = ${v.magnitude.fmt(2)}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsPanel(state: SensorWidgetsUiState, style: WidgetStyle, viewModel: SensorWidgetsViewModel) {
    val change = viewModel::onStyleChange
    SectionCard(title = "Налаштування компонентів") {
        Text("Палітра", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WidgetPalette.entries.forEach { palette ->
                FilterChip(
                    selected = palette == style.palette,
                    onClick = { change(style.copy(palette = palette)) },
                    label = { Text(palette.label, color = palette.accel) },
                )
            }
        }
        Text("Масштаб ×${style.scale.toDouble().fmt(2)}", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = style.scale,
            onValueChange = { change(style.copy(scale = it)) },
            valueRange = WidgetStyle.SCALE_RANGE,
            steps = 13,
        )
        Text("Товщина ліній ${style.strokeDp.toDouble().fmt(1)} dp", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = style.strokeDp,
            onValueChange = { change(style.copy(strokeDp = it)) },
            valueRange = WidgetStyle.STROKE_RANGE,
            steps = 9,
        )
        Text("Маркер вектора", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MarkerStyle.entries.forEach { marker ->
                FilterChip(selected = marker == style.marker, onClick = { change(style.copy(marker = marker)) }, label = { Text(marker.label) })
            }
        }
        Text("Елементи", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(style.showGrid, { change(style.copy(showGrid = !style.showGrid)) }, label = { Text("Сітка") })
            FilterChip(style.showTrail, { change(style.copy(showTrail = !style.showTrail)) }, label = { Text("Слід") })
            FilterChip(style.showAccel, { change(style.copy(showAccel = !style.showAccel)) }, label = { Text("Акселерометр") })
            FilterChip(style.showGyro, { change(style.copy(showGyro = !style.showGyro)) }, label = { Text("Гіроскоп") })
        }
    }
    SectionCard(title = "Керування") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.calibrated) {
                OutlinedButton(onClick = viewModel::resetCalibration, modifier = Modifier.weight(1f)) { Text("Скинути нуль") }
            } else {
                Button(onClick = viewModel::calibrate, modifier = Modifier.weight(1f)) { Text("Калібрувати нуль") }
            }
            OutlinedButton(onClick = viewModel::togglePause, modifier = Modifier.weight(1f)) {
                Text(if (state.paused) "Продовжити" else "Пауза")
            }
        }
        InfoRow("Оброблено вимірювань", "${state.motion.samples}")
        InfoRow("Відкинуто некоректних", "${state.motion.rejected}")
        InfoRow("Калібрування нуля", if (state.calibrated) "увімкнено" else "немає")
        InfoRow("Оновлення UI", "кожні ${SensorWidgetsViewModel.FRAME_MS} мс (sample)")
    }
}
