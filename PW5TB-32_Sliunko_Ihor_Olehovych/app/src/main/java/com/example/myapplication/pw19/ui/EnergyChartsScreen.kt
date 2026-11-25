package com.example.myapplication.pw19.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.pw19.domain.TimeInterval
import com.example.myapplication.pw19.viewmodel.ChartOptions
import com.example.myapplication.pw19.viewmodel.EnergyChartsUiState
import com.example.myapplication.pw19.viewmodel.EnergyChartsViewModel
import com.example.myapplication.ui.common.InfoRow
import com.example.myapplication.ui.common.MetricTile
import com.example.myapplication.ui.common.ScreenTitle
import com.example.myapplication.ui.common.SectionCard
import com.example.myapplication.ui.common.Tone
import com.example.myapplication.ui.common.fmt
import com.github.mikephil.charting.charts.CombinedChart
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Екран ПЗ 19: графіки та діаграми енергоспоживання лікарні з резервним живленням. */
@Composable
fun EnergyChartsScreen(viewModel: EnergyChartsViewModel = viewModel { EnergyChartsViewModel() }) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Посилання на View графіка потрібне для кнопок масштабу
    var chart by remember { mutableStateOf<CombinedChart?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val main: @Composable () -> Unit = {
            MainPanel(state, viewModel, chart, onChartReady = { chart = it })
        }
        val side: @Composable () -> Unit = { SidePanel(state) }
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScrollColumn(Modifier.weight(1.2f)) { main() }
                ScrollColumn(Modifier.weight(0.8f)) { side() }
            }
        } else {
            ScrollColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                main()
                side()
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

private val stampFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainPanel(
    state: EnergyChartsUiState,
    viewModel: EnergyChartsViewModel,
    chart: CombinedChart?,
    onChartReady: (CombinedChart) -> Unit,
) {
    ScreenTitle("Міська лікарня", "Енергоспоживання об'єкта з резервним живленням")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
        TimeInterval.entries.forEach { item ->
            FilterChip(selected = item == state.interval, onClick = { viewModel.setInterval(item) }, label = { Text(item.label) })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Живі дані (1 год обліку за 1,5 с)", style = MaterialTheme.typography.bodyMedium)
            if (state.lastHourMs > 0) {
                Text(
                    "Останній запис: ${stampFormat.format(Date(state.lastHourMs))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = state.live, onCheckedChange = viewModel::setLive)
    }
    val stats = state.stats
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricTile("Спожито", "${stats?.total?.fmt(0) ?: "—"} кВт·год", Modifier.weight(1f))
        MetricTile("Середнє / стовпчик", "${stats?.average?.fmt(0) ?: "—"} кВт·год", Modifier.weight(1f), Tone.NEUTRAL)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val peakLabel = stats?.let { state.buckets.getOrNull(it.peakIndex)?.label }.orEmpty()
        MetricTile("Пік ($peakLabel)", "${stats?.peak?.fmt(0) ?: "—"} кВт·год", Modifier.weight(1f), Tone.BAD)
        MetricTile("Частка ДГУ", "${stats?.gensetShare?.fmt(1) ?: "—"} %", Modifier.weight(1f), Tone.WARN)
    }
    SectionCard(title = "Споживання за джерелами та тренд, кВт·год") {
        EnergyComboChart(
            interval = state.interval,
            buckets = state.buckets,
            trend = state.trend,
            stats = state.stats,
            options = state.options,
            selected = state.selected,
            describe = { viewModel.describe(state, it) },
            onSelect = viewModel::select,
            onChartReady = onChartReady,
            height = 300.dp,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Масштаб", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = { chart?.zoomBy(1 / ZOOM_STEP) }) { Text("−") }
            OutlinedButton(onClick = { chart?.zoomBy(ZOOM_STEP) }) { Text("+") }
            TextButton(onClick = { chart?.fitScreen() }) { Text("Скинути") }
        }
        val options = state.options
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OptionChip("Стовпчики", options.bars) { viewModel.onOptionsChange(options.copy(bars = !options.bars)) }
            OptionChip("Тренд", options.trend) { viewModel.onOptionsChange(options.copy(trend = !options.trend)) }
            OptionChip("Середнє", options.average) { viewModel.onOptionsChange(options.copy(average = !options.average)) }
            OptionChip("Пік", options.peak) { viewModel.onOptionsChange(options.copy(peak = !options.peak)) }
        }
        Text(
            "Натисніть стовпчик або точку для підказки; щипок чи подвійне натискання — масштаб",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.selected?.let { index ->
        SectionCard(title = "Вибраний період") {
            Text(viewModel.describe(state, index), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { viewModel.select(null) }) { Text("Зняти вибір") }
        }
    }
}

@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun SidePanel(state: EnergyChartsUiState) {
    SectionCard(title = "Споживачі за ${state.interval.label.lowercase()}, кВт·год") {
        ZonesBarChart(state.zones, height = 240.dp)
    }
    SectionCard(title = "Джерела живлення") {
        SourcesPieChart(state.stats, state.buckets, height = 220.dp)
        val stats = state.stats ?: return@SectionCard
        InfoRow("Годин без мережі", "${stats.outageHours}")
        InfoRow("Частка АКБ (перехідні режими)", "${stats.batteryShare.fmt(1)} %")
        InfoRow("Витрата палива ДГУ (0,27 л/кВт·год)", "${stats.fuelLiters.fmt(0)} л")
        InfoRow("Пікова потужність", "${stats.peakPowerKw.fmt(1)} кВт")
        InfoRow("Мінімум за стовпчик", "${stats.min.fmt(0)} кВт·год")
    }
}

private const val ZOOM_STEP = 1.5f

/** Масштабування лише за віссю часу відносно центру видимої області. */
private fun CombinedChart.zoomBy(factor: Float) {
    val center = viewPortHandler.contentCenter
    zoom(factor, 1f, center.x, center.y)
}
