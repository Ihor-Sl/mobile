package com.example.myapplication.pw17.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.pw17.viewmodel.ChannelRow
import com.example.myapplication.pw17.viewmodel.PanelQuery
import com.example.myapplication.pw17.viewmodel.PanelSort
import com.example.myapplication.pw17.viewmodel.SensorPanelUiState
import com.example.myapplication.pw17.viewmodel.SensorPanelViewModel
import com.example.myapplication.sensors.AlertLevel
import com.example.myapplication.sensors.AndroidSensorSource
import com.example.myapplication.sensors.SensorChannel
import com.example.myapplication.sensors.SensorGroup
import com.example.myapplication.ui.common.InfoRow
import com.example.myapplication.ui.common.MessageBanner
import com.example.myapplication.ui.common.MetricTile
import com.example.myapplication.ui.common.ScreenTitle
import com.example.myapplication.ui.common.SectionCard
import com.example.myapplication.ui.common.StatusBadge
import com.example.myapplication.ui.common.Tone
import com.example.myapplication.ui.common.fmt

/** Екран ПЗ 17: інтерактивна панель моніторингу сенсорів щитової ДГУ. */
@Composable
fun SensorPanelScreen() {
    val context = LocalContext.current.applicationContext
    val viewModel: SensorPanelViewModel = viewModel { SensorPanelViewModel(AndroidSensorSource(context)) }
    // collectAsStateWithLifecycle: у фоні збирання зупиняється, і слухачі сенсорів знімаються
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val table: @Composable () -> Unit = {
            LivePanel(state, onExpand = viewModel::toggleExpanded, onThreshold = viewModel::setThreshold)
        }
        val controls: @Composable () -> Unit = {
            ControlPanel(state, onToggle = viewModel::toggleVisible, onQuery = viewModel::onQueryChange)
        }
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScrollColumn(Modifier.weight(1.1f)) { table() }
                ScrollColumn(Modifier.weight(0.9f)) { controls() }
            }
        } else {
            ScrollColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                table()
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

private fun AlertLevel.tone() = when (this) {
    AlertLevel.NORMAL -> Tone.GOOD
    AlertLevel.WARNING -> Tone.WARN
    AlertLevel.ALARM -> Tone.BAD
    AlertLevel.UNKNOWN -> Tone.NEUTRAL
}

@Composable
private fun LivePanel(
    state: SensorPanelUiState,
    onExpand: (SensorChannel) -> Unit,
    onThreshold: (SensorChannel, Double) -> Unit,
) {
    ScreenTitle("Щитова ДГУ", "Сенсори телефона як датчики об'єкта аварійного живлення")
    if (state.alarms.isNotEmpty()) {
        MessageBanner("Тривога: " + state.alarms.joinToString { it.channel.label }, Tone.BAD)
    }
    val shown = state.visible.size
    val warnings = state.rows.count { it.level == AlertLevel.WARNING }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricTile("На панелі", "$shown з ${SensorChannel.entries.size}", Modifier.weight(1f))
        MetricTile("Тривоги", "${state.alarms.size}", Modifier.weight(1f), if (state.alarms.isEmpty()) Tone.GOOD else Tone.BAD)
        MetricTile("Біля порогу", "$warnings", Modifier.weight(1f), if (warnings == 0) Tone.GOOD else Tone.WARN)
    }
    SectionCard(title = "Показники в реальному часі") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HeaderCell("Датчик", Modifier.weight(1.5f))
            HeaderCell("Значення", Modifier.weight(1f))
            HeaderCell("Мін…макс", Modifier.weight(1f))
            HeaderCell("Поріг", Modifier.weight(0.8f))
        }
        HorizontalDivider()
        if (state.rows.isEmpty()) {
            Text(
                if (state.visible.isEmpty()) "Оберіть сенсори для відображення" else "Немає сенсорів у вибраному діапазоні",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.rows.forEach { row ->
            SensorRowItem(row, expanded = row.channel == state.expanded, onClick = { onExpand(row.channel) }, onThreshold = onThreshold)
            HorizontalDivider()
        }
        if (state.hiddenByFilter > 0) {
            Text("Приховано фільтром: ${state.hiddenByFilter}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SensorRowItem(
    row: ChannelRow,
    expanded: Boolean,
    onClick: () -> Unit,
    onThreshold: (SensorChannel, Double) -> Unit,
) {
    val channel = row.channel
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.5f)) {
                Text(channel.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(channel.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(row.value?.let { "${it.fmt(digits(channel))} ${channel.unit}" } ?: "—", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                if (row.stats.min == null) "—" else "${row.stats.min.fmt(digits(channel))}…${row.stats.max!!.fmt(digits(channel))}",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            Column(Modifier.weight(0.8f), horizontalAlignment = Alignment.End) {
                StatusBadge(row.loadPercent?.let { "${it.fmt(0)} %" } ?: "—", row.level.tone())
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Sparkline(row.stats.history, row.threshold)
                InfoRow("Стан", row.level.label)
                InfoRow("Середнє за сеанс", row.stats.average?.let { "${it.fmt(digits(channel))} ${channel.unit}" } ?: "—")
                InfoRow("Відкинуто некоректних вимірювань", "${row.stats.rejected}")
                val direction = if (channel.alarmAbove) "вище" else "нижче"
                Text(
                    "Поріг: ${row.threshold.fmt(digits(channel))} ${channel.unit} (тривога, якщо $direction)",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = row.threshold.toFloat(),
                    onValueChange = { onThreshold(channel, it.toDouble()) },
                    valueRange = channel.thresholdRange.start.toFloat()..channel.thresholdRange.endInclusive.toFloat(),
                )
            }
        }
    }
}

/** Кількість знаків після коми залежно від масштабу величини. */
private fun digits(channel: SensorChannel) = when (channel) {
    SensorChannel.VIBRATION, SensorChannel.ROTATION, SensorChannel.BATTERY_VOLTAGE -> 2
    SensorChannel.LIGHT, SensorChannel.BATTERY_CURRENT -> 0
    else -> 1
}

/** Міні-графік історії значень (60 точок) з пунктирною лінією порогу. */
@Composable
private fun Sparkline(history: List<Double>, threshold: Double) {
    val lineColor = MaterialTheme.colorScheme.primary
    val limitColor = Color(0xFFC62828)
    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        // Шкала від нуля, щоб відстань до порогу була наочною, а не перебільшеною
        val all = history + threshold
        val min = minOf(0.0, all.min())
        val range = ((all.max() - min) * 1.15).takeIf { it > 1e-6 } ?: 1.0
        fun y(value: Double) = size.height * (1 - ((value - min) / range)).toFloat() * 0.9f + size.height * 0.05f
        val limitY = y(threshold)
        drawLine(
            limitColor, Offset(0f, limitY), Offset(size.width, limitY), strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
        )
        if (history.size < 2) return@Canvas
        val path = Path()
        history.forEachIndexed { i, value ->
            val x = size.width * i / (history.size - 1)
            if (i == 0) path.moveTo(x, y(value)) else path.lineTo(x, y(value))
        }
        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ControlPanel(
    state: SensorPanelUiState,
    onToggle: (SensorChannel) -> Unit,
    onQuery: (PanelQuery) -> Unit,
) {
    val query = state.query
    SectionCard(title = "Сенсори на панелі") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SensorChannel.entries.forEach { channel ->
                val available = channel in state.available
                FilterChip(
                    selected = channel in state.visible,
                    onClick = { onToggle(channel) },
                    enabled = available,
                    label = { Text(if (available) channel.label else "${channel.label} (н/д)") },
                )
            }
        }
    }
    SectionCard(title = "Фільтр і сортування") {
        Text("Тип сенсора", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SensorGroup.entries.forEach { group ->
                FilterChip(
                    selected = group in query.groups,
                    onClick = { onQuery(query.copy(groups = if (group in query.groups) query.groups - group else query.groups + group)) },
                    label = { Text(group.label) },
                )
            }
        }
        val end = if (query.loadRange.endInclusive >= PanelQuery.MAX_RANGE) "150+" else query.loadRange.endInclusive.fmt(0)
        Text("Діапазон значень: ${query.loadRange.start.fmt(0)} … $end % порогу", style = MaterialTheme.typography.labelLarge)
        RangeSlider(
            value = query.loadRange,
            onValueChange = { onQuery(query.copy(loadRange = it)) },
            valueRange = 0f..PanelQuery.MAX_RANGE,
            steps = 29,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Усі" to 0f, "≥ 85 %" to 85f, "≥ 100 %" to 100f).forEach { (label, from) ->
                FilterChip(
                    selected = query.loadRange.start == from && query.loadRange.endInclusive == PanelQuery.MAX_RANGE,
                    onClick = { onQuery(query.copy(loadRange = from..PanelQuery.MAX_RANGE)) },
                    label = { Text(label) },
                )
            }
        }
        Text("Сортування", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelSort.entries.forEach { sort ->
                FilterChip(selected = sort == query.sort, onClick = { onQuery(query.copy(sort = sort)) }, label = { Text(sort.label) })
            }
            FilterChip(
                selected = true,
                onClick = { onQuery(query.copy(descending = !query.descending)) },
                label = { Text(if (query.descending) "↓ спадання" else "↑ зростання") },
            )
        }
    }
    SectionCard(title = "Журнал подій") {
        if (state.events.isEmpty()) Text("Перетинів порогу ще не було", style = MaterialTheme.typography.bodySmall)
        state.events.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun Float.fmt(decimals: Int) = toDouble().fmt(decimals)
