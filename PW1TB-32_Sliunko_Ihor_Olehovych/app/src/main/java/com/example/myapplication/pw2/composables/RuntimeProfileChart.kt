package com.example.myapplication.pw2.composables

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.pw2.data.EmergencyPowerCalculator
import com.example.myapplication.pw2.data.ProfilePoint

/** Кількість поділок осі часу (X) та горизонтальних ліній сітки осі відсотків (Y). */
private const val X_TICKS = 4
private val Y_TICKS = listOf(0, 25, 50, 75, 100)

/**
 * Графік профілю автономної роботи, намальований нативно на Compose Canvas (без сторонніх бібліотек).
 * Дві лінії: заряд АКБ (%) та рівень палива в баку ДГУ (%) залежно від часу (год).
 * Додатково: сітка з підписами осей, пунктирна лінія мінімального резерву АКБ,
 * напівпрозора заливка під лініями та плавна анімація прорисовки зліва направо.
 *
 * Анімація перезапускається при зміні [points] (новий розрахунок).
 */
@Composable
fun RuntimeProfileChart(points: List<ProfilePoint>, modifier: Modifier = Modifier) {
    val batteryColor = MaterialTheme.colorScheme.primary
    val fuelColor = MaterialTheme.colorScheme.tertiary
    val reserveColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val textMeasurer = rememberTextMeasurer()

    // Прогрес прорисовки 0…1: скидається й запускається заново для кожного нового набору точок
    val drawProgress = remember(points) { Animatable(0f) }
    LaunchedEffect(points) { drawProgress.animateTo(1f, tween(durationMillis = 900)) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        ) {
            // Внутрішні поля під підписи осей
            val left = 40.dp.toPx()
            val right = 8.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = 24.dp.toPx()
            val plotWidth = size.width - left - right
            val plotHeight = size.height - top - bottom
            val totalHours = points.lastOrNull()?.timeHours ?: 0.0

            fun xOf(hours: Double): Float =
                left + if (totalHours > 0) (hours / totalHours).toFloat() * plotWidth else 0f

            fun yOf(percent: Double): Float =
                top + plotHeight * (1f - (percent / 100.0).toFloat().coerceIn(0f, 1f))

            drawGridAndAxes(textMeasurer, labelStyle, gridColor, left, top, plotWidth, plotHeight, totalHours)

            // Лінія мінімального резерву АКБ
            val reserveY = yOf(EmergencyPowerCalculator.BATTERY_RESERVE_PERCENT)
            drawLine(
                color = reserveColor.copy(alpha = 0.7f),
                start = Offset(left, reserveY),
                end = Offset(left + plotWidth, reserveY),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            )
            drawText(
                textMeasurer = textMeasurer,
                text = "резерв",
                topLeft = Offset(left + 4.dp.toPx(), reserveY - 14.dp.toPx()),
                style = labelStyle.copy(color = reserveColor)
            )

            // Ділянка графіка відкривається пропорційно прогресу анімації
            clipRect(left = left, top = 0f, right = left + plotWidth * drawProgress.value, bottom = size.height) {
                val baselineY = top + plotHeight
                drawSeries(points, batteryColor, { it.batteryPercent }, ::xOf, ::yOf, baselineY)
                drawSeries(points, fuelColor, { it.fuelPercent }, ::xOf, ::yOf, baselineY)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendItem(batteryColor, "Заряд АКБ, %")
            LegendItem(fuelColor, "Паливо, %")
            Text(
                text = "вісь X — години",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Малює горизонтальну сітку з підписами відсотків та вертикальні поділки з підписами часу. */
private fun DrawScope.drawGridAndAxes(
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    gridColor: Color,
    left: Float,
    top: Float,
    plotWidth: Float,
    plotHeight: Float,
    totalHours: Double,
) {
    // Горизонтальні лінії та підписи осі Y (вирівнювання підпису по правому краю)
    Y_TICKS.forEach { percent ->
        val y = top + plotHeight * (1f - percent / 100f)
        drawLine(gridColor, Offset(left, y), Offset(left + plotWidth, y), strokeWidth = 1.dp.toPx())
        val label = "$percent%"
        val labelSize = textMeasurer.measure(label, labelStyle).size
        drawText(
            textMeasurer = textMeasurer,
            text = label,
            topLeft = Offset(left - 6.dp.toPx() - labelSize.width, y - labelSize.height / 2f),
            style = labelStyle
        )
    }
    // Вертикальні поділки та підписи осі X (центрування підпису під поділкою)
    for (i in 0..X_TICKS) {
        val fraction = i / X_TICKS.toFloat()
        val x = left + plotWidth * fraction
        drawLine(
            gridColor,
            Offset(x, top + plotHeight),
            Offset(x, top + plotHeight + 4.dp.toPx()),
            strokeWidth = 1.dp.toPx()
        )
        val label = "%.1f".format(totalHours * fraction)
        val labelSize = textMeasurer.measure(label, labelStyle).size
        drawText(
            textMeasurer = textMeasurer,
            text = label,
            topLeft = Offset(
                (x - labelSize.width / 2f).coerceIn(0f, size.width - labelSize.width),
                top + plotHeight + 6.dp.toPx()
            ),
            style = labelStyle
        )
    }
}

/**
 * Малює одну серію: лінію та напівпрозору заливку під нею.
 * Яке саме значення відкладати по осі Y (заряд АКБ чи паливо), визначає [value] —
 * так код побудови шляху не дублюється для обох ліній.
 */
private fun DrawScope.drawSeries(
    points: List<ProfilePoint>,
    color: Color,
    value: (ProfilePoint) -> Double,
    xOf: (Double) -> Float,
    yOf: (Double) -> Float,
    baselineY: Float,
) {
    if (points.isEmpty()) return

    // Єдина точка (нульова автономність) — просто маркер
    if (points.size == 1) {
        drawCircle(color, radius = 4.dp.toPx(), center = Offset(xOf(points[0].timeHours), yOf(value(points[0]))))
        return
    }

    val line = Path().apply {
        points.forEachIndexed { index, p ->
            val x = xOf(p.timeHours)
            val y = yOf(value(p))
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
    // Заливка: копія лінії, замкнена вниз до осі X
    val area = Path().apply {
        addPath(line)
        lineTo(xOf(points.last().timeHours), baselineY)
        lineTo(xOf(points.first().timeHours), baselineY)
        close()
    }
    drawPath(
        path = area,
        brush = Brush.verticalGradient(
            colors = listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f)),
            startY = 0f,
            endY = baselineY
        )
    )
    drawPath(
        path = line,
        color = color,
        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

/** Елемент легенди: кольорова крапка та підпис. */
@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}
