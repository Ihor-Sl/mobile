package com.example.myapplication.pw19.ui

import android.content.Context
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.myapplication.R
import com.example.myapplication.pw19.domain.EnergyBucket
import com.example.myapplication.pw19.domain.EnergyStats
import com.example.myapplication.pw19.domain.TimeInterval
import com.example.myapplication.pw19.domain.ZoneUsage
import com.example.myapplication.pw19.viewmodel.ChartOptions
import com.github.mikephil.charting.charts.BarLineChartBase
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.charts.HorizontalBarChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.CombinedData
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.utils.MPPointF
import kotlin.math.roundToInt

/** Кольори джерел живлення, спільні для стовпчиків, кругової діаграми та легенди. */
object EnergyColors {
    val GRID = Color(0xFF1E88E5)
    val GENSET = Color(0xFFFB8C00)
    val BATTERY = Color(0xFF43A047)
    val TREND = Color(0xFF8E24AA)
    val AVERAGE = Color(0xFF546E7A)
    val PEAK = Color(0xFFC62828)
    val ZONES = listOf(0xFF5C6BC0, 0xFF26A69A, 0xFFFFA726, 0xFFFFCA28, 0xFF42A5F5, 0xFF8D6E63).map(::Color)
}

/**
 * Підказка (tooltip) MPAndroidChart: текст для точки формує [describe], а getOffset
 * розміщує вікно над точкою. Бібліотека сама зсуває його, щоб не виходило за межі графіка.
 */
class EnergyMarkerView(context: Context) : MarkerView(context, R.layout.chart_marker) {
    private val text: TextView = findViewById(R.id.marker_text)
    var describe: (Int) -> String = { "" }

    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        text.text = e?.let { describe(it.x.roundToInt()) }.orEmpty()
        super.refreshContent(e, highlight)
    }

    override fun getOffset(): MPPointF = MPPointF(-(width / 2f), -height.toFloat() - 8f)
}

/** Спільні налаштування осей і легенди під тему застосунку. */
private fun BarLineChartBase<*>.applyTheme(text: Int, grid: Int) {
    description.isEnabled = false
    setNoDataText("Немає даних")
    axisRight.isEnabled = false
    axisLeft.textColor = text
    axisLeft.gridColor = grid
    axisLeft.axisMinimum = 0f
    xAxis.textColor = text
    xAxis.position = XAxis.XAxisPosition.BOTTOM
    xAxis.setDrawGridLines(false)
    xAxis.granularity = 1f
    xAxis.isGranularityEnabled = true
    legend.textColor = text
    legend.isWordWrapEnabled = true
}

private fun selectionListener(onSelect: (Int?) -> Unit) = object : OnChartValueSelectedListener {
    override fun onValueSelected(e: Entry?, h: Highlight?) = onSelect(e?.x?.roundToInt())
    override fun onNothingSelected() = onSelect(null)
}

/**
 * Комбінований графік: стовпчики з розподілом за джерелами та лінія тренду для одного набору даних,
 * лінії середнього і пікового значення. Масштабування — щипком, подвійним натисканням або кнопками.
 */
@Composable
fun EnergyComboChart(
    interval: TimeInterval,
    buckets: List<EnergyBucket>,
    trend: List<Double>,
    stats: EnergyStats?,
    options: ChartOptions,
    selected: Int?,
    describe: (Int) -> String,
    onSelect: (Int?) -> Unit,
    onChartReady: (CombinedChart) -> Unit,
    height: Dp,
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val gridColor = MaterialTheme.colorScheme.outlineVariant.toArgb()
    val currentSelect by rememberUpdatedState(onSelect)
    val currentDescribe by rememberUpdatedState(describe)
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(height),
        factory = { context ->
            CombinedChart(context).apply {
                applyTheme(textColor, gridColor)
                drawOrder = arrayOf(CombinedChart.DrawOrder.BAR, CombinedChart.DrawOrder.LINE)
                // highlightFullBar у CombinedChart не вмикається: MPAndroidChart 3.1.0 тоді створює
                // Highlight без індексу набору даних і падає з IndexOutOfBounds під час натискання
                setScaleEnabled(true)
                isScaleYEnabled = false // масштабування лише за часом
                isDoubleTapToZoomEnabled = true
                isDragEnabled = true
                axisLeft.setDrawLimitLinesBehindData(false) // лінії показників поверх стовпчиків
                marker = EnergyMarkerView(context).also { it.chartView = this }
                setOnChartValueSelectedListener(selectionListener { currentSelect(it) })
                onChartReady(this)
            }
        },
        update = { chart ->
            // Дані перебудовуються лише тоді, коли вони змінились, а не при кожному виборі стовпчика
            val memo = chart.tag as? ChartMemo
            val key = listOf(buckets, trend, stats, options)
            (chart.marker as? EnergyMarkerView)?.describe = { currentDescribe(it) }
            if (memo?.key == key) {
                if (selected == null) chart.highlightValues(null)
                return@AndroidView
            }
            val labels = buckets.map { it.label }
            val barSet = BarDataSet(
                buckets.mapIndexed { i, b -> BarEntry(i.toFloat(), floatArrayOf(b.grid.toFloat(), b.genset.toFloat(), b.battery.toFloat())) },
                "",
            ).apply {
                colors = listOf(EnergyColors.GRID, EnergyColors.GENSET, EnergyColors.BATTERY).map { it.toArgb() }
                stackLabels = arrayOf("Мережа", "ДГУ", "АКБ")
                setDrawValues(false)
                highLightAlpha = 70
            }
            val lineSet = LineDataSet(trend.mapIndexed { i, v -> Entry(i.toFloat(), v.toFloat()) }, "Тренд (ковзне середнє)").apply {
                color = EnergyColors.TREND.toArgb()
                lineWidth = 2.5f
                setDrawCircles(trend.size <= 31)
                circleRadius = 2.5f
                setCircleColor(EnergyColors.TREND.toArgb())
                setDrawValues(false)
                mode = LineDataSet.Mode.HORIZONTAL_BEZIER
                setDrawHorizontalHighlightIndicator(false)
                highLightColor = EnergyColors.TREND.toArgb()
            }
            chart.data = CombinedData().apply {
                if (options.bars) setData(BarData(barSet).apply { barWidth = 0.7f })
                if (options.trend) setData(LineData(lineSet))
            }
            chart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            chart.xAxis.axisMinimum = -0.5f
            chart.xAxis.axisMaximum = buckets.size - 0.5f
            chart.xAxis.labelRotationAngle = if (interval == TimeInterval.WEEK) -35f else 0f

            // Додаткові показники як горизонтальні лінії: середнє та пік
            chart.axisLeft.removeAllLimitLines()
            if (stats != null && options.average) {
                chart.axisLeft.addLimitLine(LimitLine(stats.average.toFloat(), "Середнє %.0f".format(stats.average)).apply {
                    lineColor = EnergyColors.AVERAGE.toArgb()
                    setTextColor(EnergyColors.AVERAGE.toArgb())
                    lineWidth = 1.5f
                    enableDashedLine(14f, 10f, 0f)
                    labelPosition = LimitLine.LimitLabelPosition.LEFT_TOP
                })
            }
            if (stats != null && options.peak) {
                chart.axisLeft.addLimitLine(LimitLine(stats.peak.toFloat(), "Пік %.0f".format(stats.peak)).apply {
                    lineColor = EnergyColors.PEAK.toArgb()
                    setTextColor(EnergyColors.PEAK.toArgb())
                    lineWidth = 1.5f
                    labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
                })
            }
            chart.axisLeft.axisMaximum = ((stats?.peak ?: 1.0) * 1.15).toFloat()

            // Новий інтервал — новий ряд: масштаб і виділення скидаються
            if (memo?.interval != interval) chart.fitScreen()
            chart.tag = ChartMemo(interval, key)
            if (selected == null) chart.highlightValues(null)
            chart.notifyDataSetChanged()
            chart.invalidate()
        },
    )
}

private data class ChartMemo(val interval: TimeInterval, val key: List<Any?>)

/** Стовпчиковий графік порівняння груп споживачів із підказкою при натисканні. */
@Composable
fun ZonesBarChart(zones: List<ZoneUsage>, height: Dp) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val gridColor = MaterialTheme.colorScheme.outlineVariant.toArgb()
    val current by rememberUpdatedState(zones)
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(height),
        factory = { context ->
            HorizontalBarChart(context).apply {
                applyTheme(textColor, gridColor)
                legend.isEnabled = false
                setScaleEnabled(false)
                setDrawValueAboveBar(true)
                setFitBars(true)
                marker = EnergyMarkerView(context).also {
                    it.chartView = this
                    it.describe = { index ->
                        current.getOrNull(index)?.let { z -> "%s\n%.0f кВт·год · %.1f %%".format(z.zone.label, z.kwh, z.share) }.orEmpty()
                    }
                }
            }
        },
        update = { chart ->
            val set = BarDataSet(zones.mapIndexed { i, z -> BarEntry(i.toFloat(), z.kwh.toFloat()) }, "Споживання").apply {
                colors = EnergyColors.ZONES.map { it.toArgb() }
                valueTextColor = textColor
                valueTextSize = 10f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) = "%.0f".format(value)
                }
            }
            chart.data = BarData(set).apply { barWidth = 0.65f }
            chart.xAxis.valueFormatter = IndexAxisValueFormatter(zones.map { it.zone.label })
            chart.xAxis.labelCount = zones.size
            chart.axisLeft.axisMaximum = (zones.maxOfOrNull { it.kwh } ?: 1.0).toFloat() * 1.2f
            chart.notifyDataSetChanged()
            chart.invalidate()
        },
    )
}

/** Кругова діаграма часток джерел живлення за інтервал. */
@Composable
fun SourcesPieChart(stats: EnergyStats?, buckets: List<EnergyBucket>, height: Dp) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val holeColor = MaterialTheme.colorScheme.surfaceContainerLow.toArgb()
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(height),
        factory = { context ->
            PieChart(context).apply {
                description.isEnabled = false
                setUsePercentValues(true)
                isDrawHoleEnabled = true
                holeRadius = 50f
                transparentCircleRadius = 55f
                setHoleColor(holeColor)
                setCenterTextColor(textColor)
                setCenterTextSize(13f)
                setDrawEntryLabels(false)
                legend.textColor = textColor
                legend.isWordWrapEnabled = true
                setExtraOffsets(24f, 8f, 24f, 8f)
                // Малі сегменти починаються зліва, де підписи розходяться по вертикалі
                rotationAngle = 180f
                isRotationEnabled = false
            }
        },
        update = { chart ->
            val sources = listOf(
                Triple("Мережа", buckets.sumOf { it.grid }, EnergyColors.GRID),
                Triple("ДГУ", buckets.sumOf { it.genset }, EnergyColors.GENSET),
                Triple("АКБ", buckets.sumOf { it.battery }, EnergyColors.BATTERY),
            ).filter { it.second > 0 }
            val set = PieDataSet(sources.map { PieEntry(it.second.toFloat(), it.first) }, "").apply {
                colors = sources.map { it.third.toArgb() }
                sliceSpace = 2f
                valueTextSize = 12f
                // Відсотки винесено за межі сегментів: малі частки (АКБ) не перекривають сусідні підписи
                yValuePosition = PieDataSet.ValuePosition.OUTSIDE_SLICE
                valueLinePart1Length = 0.45f
                valueLinePart2Length = 0.35f
                valueLineColor = textColor
                valueTextColor = textColor
                valueFormatter = PercentFormatter(chart)
            }
            chart.data = PieData(set)
            chart.centerText = stats?.let { "%.0f\nкВт·год".format(it.total) } ?: ""
            chart.invalidate()
        },
    )
}
