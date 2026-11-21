package com.example.myapplication.pw13.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.pw13.data.PowerScenario
import com.example.myapplication.pw13.domain.LinkState
import com.example.myapplication.pw13.domain.PowerAssessor
import com.example.myapplication.pw13.domain.PowerReading
import com.example.myapplication.pw13.domain.SensorId
import com.example.myapplication.pw13.domain.SupplyMode
import com.example.myapplication.pw13.viewmodel.LoadPoint
import com.example.myapplication.pw13.viewmodel.PowerFlowViewModel
import com.example.myapplication.pw13.viewmodel.PowerUiState
import com.example.myapplication.ui.common.MessageBanner
import com.example.myapplication.ui.common.MetricTile
import com.example.myapplication.ui.common.ScreenTitle
import com.example.myapplication.ui.common.SectionCard
import com.example.myapplication.ui.common.StatusBadge
import com.example.myapplication.ui.common.Tone
import com.example.myapplication.ui.common.fmt

/** Екран ПЗ 13: показники датчиків оновлюються в реальному часі зі StateFlow ViewModel. */
@Composable
fun PowerFlowScreen(viewModel: PowerFlowViewModel = viewModel { PowerFlowViewModel() }) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scenario by viewModel.scenario.collectAsStateWithLifecycle()
    val thresholdText by viewModel.thresholdText.collectAsStateWithLifecycle()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val live: @Composable () -> Unit = { LivePanel(state) }
        val controls: @Composable () -> Unit = {
            ControlPanel(
                state = state,
                scenario = scenario,
                thresholdText = thresholdText,
                onThreshold = viewModel::onThresholdChange,
                onScenario = viewModel::setScenario,
                onTransient = viewModel::injectTransientFault,
                onPermanent = viewModel::injectPermanentFault,
                onReconnect = viewModel::reconnect,
            )
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScrollColumn(Modifier.weight(1f)) { live() }
                ScrollColumn(Modifier.weight(1f)) { controls() }
            }
        } else {
            ScrollColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                live()
                controls()
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
private fun LivePanel(state: PowerUiState) {
    ScreenTitle("Насосна станція", "Потоки датчиків ДГУ, АКБ і мережі")
    val reading = state.reading
    if (reading == null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.height(24.dp))
            Text("Очікування даних від датчиків…")
        }
        return
    }
    if (state.belowThreshold) {
        MessageBanner("Автономність ${reading.autonomyHours.fmt()} год нижча за поріг ${state.thresholdHours.fmt()} год", Tone.BAD)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricTile("Джерело", reading.mode.label, Modifier.weight(1f), modeTone(reading.mode))
        MetricTile("Автономність", "${reading.autonomyHours.fmt()} год", Modifier.weight(1f), if (state.belowThreshold) Tone.BAD else Tone.GOOD)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricTile("Мережа", "${reading.voltage.fmt()} В", Modifier.weight(1f), if (reading.voltage in PowerAssessor.NORMAL_VOLTAGE) Tone.GOOD else Tone.BAD)
        MetricTile("Паливо", "${reading.fuelLiters.fmt()} л", Modifier.weight(1f), if (reading.fuelLiters > 60) Tone.GOOD else Tone.WARN)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricTile("АКБ", "${reading.batteryPercent.fmt()} %", Modifier.weight(1f))
        MetricTile("Навантаження", "${reading.loadKw.fmt()} кВт", Modifier.weight(1f))
    }
    SectionCard(title = "Навантаження: факт і прогноз ${state.forecastKw?.fmt() ?: "—"} кВт") {
        LoadChart(state.history)
    }
}

private fun modeTone(mode: SupplyMode) = when (mode) {
    SupplyMode.GRID -> Tone.GOOD
    SupplyMode.GENSET, SupplyMode.BATTERY -> Tone.WARN
    SupplyMode.BLACKOUT -> Tone.BAD
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlPanel(
    state: PowerUiState,
    scenario: PowerScenario,
    thresholdText: String,
    onThreshold: (String) -> Unit,
    onScenario: (PowerScenario) -> Unit,
    onTransient: (SensorId) -> Unit,
    onPermanent: (SensorId) -> Unit,
    onReconnect: (SensorId) -> Unit,
) {
    var faultSensor by rememberSaveable { mutableStateOf(SensorId.FUEL) }
    SectionCard(title = "Сценарій і поріг") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PowerScenario.entries.forEach { item ->
                FilterChip(selected = item == scenario, onClick = { onScenario(item) }, label = { Text(item.label) })
            }
        }
        val invalid = PowerAssessor.parseThreshold(thresholdText) == null
        OutlinedTextField(
            value = thresholdText,
            onValueChange = onThreshold,
            label = { Text("Поріг автономності, год") },
            isError = invalid,
            supportingText = { Text(if (invalid) "Від 1 до 72 год" else "Застосовується через 300 мс (debounce)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    SectionCard(title = "Стан потоків") {
        SensorId.entries.forEach { id ->
            val link = state.links.getValue(id)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(id.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (link is LinkState.Failed) {
                    TextButton(onClick = { onReconnect(id) }) { Text("Підключити") }
                }
                when (link) {
                    LinkState.Online -> StatusBadge("На зв'язку", Tone.GOOD)
                    is LinkState.Retrying -> StatusBadge("Повтор ${link.attempt}/${link.maxAttempts}", Tone.WARN)
                    is LinkState.Failed -> StatusBadge("Немає зв'язку", Tone.BAD)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SensorId.entries.forEach { id ->
                FilterChip(selected = id == faultSensor, onClick = { faultSensor = id }, label = { Text(id.label.substringBefore(' ')) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onTransient(faultSensor) }, modifier = Modifier.weight(1f)) { Text("Короткий збій") }
            OutlinedButton(onClick = { onPermanent(faultSensor) }, modifier = Modifier.weight(1f)) { Text("Тривалий збій") }
        }
    }
    SectionCard(title = "Журнал подій") {
        if (state.events.isEmpty()) {
            Text("Подій ще немає", style = MaterialTheme.typography.bodySmall)
        }
        state.events.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Лінійний графік: суцільна лінія — факт, світліша — прогноз. */
@Composable
private fun LoadChart(points: List<LoadPoint>) {
    val actualColor = MaterialTheme.colorScheme.primary
    val forecastColor = Color(0xFFE08A00)
    Canvas(Modifier.fillMaxWidth().height(96.dp)) {
        if (points.size < 2) return@Canvas
        val values = points.flatMap { listOf(it.actual, it.forecast) }
        val min = values.min() - 1
        val range = (values.max() + 1 - min).coerceAtLeast(1.0)
        fun position(index: Int, value: Double) = Offset(
            x = size.width * index / (points.size - 1),
            y = size.height * (1 - ((value - min) / range)).toFloat(),
        )
        fun line(selector: (LoadPoint) -> Double) = Path().apply {
            points.forEachIndexed { i, p ->
                val o = position(i, selector(p))
                if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
            }
        }
        drawPath(line { it.forecast }, forecastColor, style = Stroke(width = 2.dp.toPx()))
        drawPath(line { it.actual }, actualColor, style = Stroke(width = 2.5.dp.toPx()))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("— факт", color = actualColor, style = MaterialTheme.typography.labelSmall)
        Text("— прогноз", color = forecastColor, style = MaterialTheme.typography.labelSmall)
    }
}
