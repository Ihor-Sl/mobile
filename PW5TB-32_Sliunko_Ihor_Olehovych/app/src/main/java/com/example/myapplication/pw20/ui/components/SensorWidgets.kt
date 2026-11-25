package com.example.myapplication.pw20.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.example.myapplication.pw20.domain.MotionMath
import com.example.myapplication.pw20.domain.Tilt
import com.example.myapplication.sensors.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Користувацькі компоненти ПЗ 20. Кожен компонент — @Composable-функція з Canvas, яка
 * отримує дані сенсора та WidgetStyle як параметри; значення анімуються (animate*AsState),
 * тому стрілки й бульбашка рухаються плавно навіть за рідкісних оновлень сенсора.
 */

private val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))

/** Центрує текст відносно точки [at]. */
private fun DrawScope.centeredText(measurer: TextMeasurer, text: String, at: Offset, style: TextStyle) {
    val layout = measurer.measure(text, style)
    drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
}

/** Обмежує нормовану точку одиничним колом. */
private fun Offset.clipToUnit(): Offset {
    val length = sqrt(x * x + y * y)
    return if (length > 1f) Offset(x / length, y / length) else this
}

/**
 * Бульбашковий рівень рами ДГУ (базовий рівень): бульбашка зміщується до вищого краю,
 * пунктирне коло — допустимий нахил [limitDeg]. Край шкали — 20° / масштаб.
 */
@Composable
fun TiltLevel(tilt: Tilt, limitDeg: Float, style: WidgetStyle, modifier: Modifier = Modifier) {
    val rangeDeg = 20f / style.scale
    val target = Offset((tilt.rollDeg / rangeDeg).toFloat(), (-tilt.pitchDeg / rangeDeg).toFloat()).clipToUnit()
    val bubble by animateOffsetAsState(target, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow), label = "bubble")
    val over = tilt.totalDeg > limitDeg
    val bubbleColor by animateColorAsState(if (over) style.palette.alarm else style.palette.ok, label = "bubbleColor")
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outline
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(modifier.aspectRatio(1f)) {
        val r = size.minDimension / 2f * 0.95f
        val c = center
        val stroke = style.strokeDp.dp.toPx()
        drawCircle(surface, r, c)
        if (style.showGrid) {
            drawLine(outline, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
            drawLine(outline, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
            drawCircle(outline, r / 2, c, style = Stroke(1.dp.toPx(), pathEffect = dash))
        }
        val limitR = r * (limitDeg / rangeDeg).coerceAtMost(1f)
        drawCircle(style.palette.ok, limitR, c, style = Stroke(stroke, pathEffect = dash))
        drawCircle(outline, r, c, style = Stroke(1.5.dp.toPx()))
        val bubbleR = r * 0.17f
        val position = c + Offset(bubble.x * (r - bubbleR), bubble.y * (r - bubbleR))
        drawCircle(bubbleColor.copy(alpha = 0.85f), bubbleR, position)
        drawCircle(Color.White.copy(alpha = 0.55f), bubbleR * 0.32f, position + Offset(-bubbleR * 0.35f, -bubbleR * 0.35f))
        centeredText(measurer, "±%.0f°".format(limitDeg), Offset(c.x + limitR * 0.72f, c.y - limitR * 0.72f - 10.dp.toPx()), labelStyle)
    }
}

/**
 * Дугова шкала (середній рівень): дуга 240° із зонами «норма/попередження/аварія»,
 * заповнення та стрілка плавно анімуються до нового значення.
 */
@Composable
fun ArcGauge(
    value: Double,
    max: Double,
    warn: Double,
    alarm: Double,
    title: String,
    unit: String,
    style: WidgetStyle,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(value.coerceIn(0.0, max).toFloat(), tween(durationMillis = 350), label = "gauge")
    val color by animateColorAsState(
        when {
            animated >= alarm -> style.palette.alarm
            animated >= warn -> style.palette.warn
            else -> style.palette.ok
        },
        label = "gaugeColor",
    )
    val track = MaterialTheme.colorScheme.surfaceVariant
    val needle = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val valueStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(modifier.aspectRatio(1.15f)) {
        val thick = style.strokeDp.dp.toPx() * 3
        val diameter = min(size.width, size.height / 0.78f) - thick * 2
        val topLeft = Offset((size.width - diameter) / 2f, thick)
        val arcSize = Size(diameter, diameter)
        val c = topLeft + Offset(diameter / 2f, diameter / 2f)
        fun sweep(v: Double) = (SWEEP * (v / max)).toFloat().coerceIn(0f, SWEEP)

        drawArc(track, START, SWEEP, false, topLeft, arcSize, style = Stroke(thick, cap = StrokeCap.Round))
        // Тонкі дуги зон по зовнішньому краю шкали
        val zoneInset = thick * 0.95f
        val zoneTopLeft = topLeft - Offset(zoneInset, zoneInset)
        val zoneSize = Size(diameter + zoneInset * 2, diameter + zoneInset * 2)
        val zoneStroke = Stroke(thick / 4)
        drawArc(style.palette.ok, START, sweep(warn), false, zoneTopLeft, zoneSize, style = zoneStroke)
        drawArc(style.palette.warn, START + sweep(warn), sweep(alarm) - sweep(warn), false, zoneTopLeft, zoneSize, style = zoneStroke)
        drawArc(style.palette.alarm, START + sweep(alarm), SWEEP - sweep(alarm), false, zoneTopLeft, zoneSize, style = zoneStroke)

        drawArc(color, START, sweep(animated.toDouble()), false, topLeft, arcSize, style = Stroke(thick, cap = StrokeCap.Round))
        val angle = Math.toRadians((START + sweep(animated.toDouble())).toDouble())
        val length = diameter / 2f - thick * 1.2f
        drawLine(needle, c, c + Offset((cos(angle) * length).toFloat(), (sin(angle) * length).toFloat()), style.strokeDp.dp.toPx(), StrokeCap.Round)
        drawCircle(needle, style.strokeDp.dp.toPx() * 2, c)
        centeredText(measurer, "%.2f".format(animated), c + Offset(0f, diameter * 0.14f), valueStyle)
        centeredText(measurer, "$unit · $title", c + Offset(0f, diameter * 0.3f), labelStyle)
    }
}

private const val START = 150f
private const val SWEEP = 240f

/**
 * Векторний компас (просунутий рівень): проєкції XY прискорення та кутової швидкості
 * одночасно, із «слідом» останніх значень. Кільця відповідають чвертям повної шкали.
 */
@Composable
fun VectorScope(
    accel: Vec3,
    gyro: Vec3,
    accelTrail: List<Vec3>,
    gyroTrail: List<Vec3>,
    accelFullScale: Double,
    gyroFullScale: Double,
    style: WidgetStyle,
    modifier: Modifier = Modifier,
) {
    fun project(v: Vec3, full: Double) = MotionMath.toScope(v.x, v.y, full, style.scale.toDouble())
        .let { (x, y) -> Offset(x.toFloat(), -y.toFloat()) } // вісь Y екрана спрямована вниз
    val accelTip by animateOffsetAsState(project(accel, accelFullScale), spring(stiffness = Spring.StiffnessMediumLow), label = "accelTip")
    val gyroTip by animateOffsetAsState(project(gyro, gyroFullScale), spring(stiffness = Spring.StiffnessMediumLow), label = "gyroTip")
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outline
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(modifier.aspectRatio(1f)) {
        val r = size.minDimension / 2f * 0.86f
        val c = center
        val stroke = style.strokeDp.dp.toPx()
        drawCircle(surface, r, c)
        if (style.showGrid) {
            listOf(0.25f, 0.5f, 0.75f).forEach { drawCircle(outline.copy(alpha = 0.6f), r * it, c, style = Stroke(1.dp.toPx(), pathEffect = dash)) }
            drawLine(outline, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
            drawLine(outline, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
            centeredText(measurer, "+X", Offset(c.x + r + 12.dp.toPx(), c.y), labelStyle)
            centeredText(measurer, "+Y", Offset(c.x, c.y - r - 10.dp.toPx()), labelStyle)
            centeredText(
                measurer,
                "край: %.1f м/с² · %.2f рад/с".format(accelFullScale / style.scale, gyroFullScale / style.scale),
                Offset(c.x, c.y + r + 10.dp.toPx()),
                labelStyle,
            )
        }
        drawCircle(outline, r, c, style = Stroke(1.5.dp.toPx()))
        if (style.showTrail) {
            if (style.showAccel) drawTrail(accelTrail.map { project(it, accelFullScale) }, c, r, style.palette.accel, stroke)
            if (style.showGyro) drawTrail(gyroTrail.map { project(it, gyroFullScale) }, c, r, style.palette.gyro, stroke)
        }
        if (style.showAccel) drawVector(c, c + accelTip * r, style.palette.accel, stroke, style.marker)
        if (style.showGyro) drawVector(c, c + gyroTip * r, style.palette.gyro, stroke, style.marker)
        drawCircle(outline, stroke, c)
    }
}

/** Слід: старіші точки прозоріші й менші. */
private fun DrawScope.drawTrail(points: List<Offset>, c: Offset, r: Float, color: Color, stroke: Float) {
    points.forEachIndexed { i, p ->
        val age = (i + 1f) / points.size
        drawCircle(color.copy(alpha = 0.15f + 0.45f * age), stroke * (0.4f + 0.6f * age), c + p * r)
    }
}

private fun DrawScope.drawVector(from: Offset, to: Offset, color: Color, stroke: Float, marker: MarkerStyle) {
    when (marker) {
        MarkerStyle.ARROW -> {
            drawLine(color, from, to, stroke, StrokeCap.Round)
            val dx = to.x - from.x
            val dy = to.y - from.y
            if (dx * dx + dy * dy < (stroke * 3) * (stroke * 3)) return
            val angle = atan2(dy, dx)
            val head = stroke * 4.5f
            val path = Path().apply {
                moveTo(to.x, to.y)
                lineTo(to.x - head * cos(angle - 0.45f), to.y - head * sin(angle - 0.45f))
                lineTo(to.x - head * cos(angle + 0.45f), to.y - head * sin(angle + 0.45f))
                close()
            }
            drawPath(path, color)
        }
        MarkerStyle.DOT -> {
            drawLine(color.copy(alpha = 0.35f), from, to, stroke / 2)
            drawCircle(color, stroke * 2.4f, to)
        }
    }
}

/** Графік трьох осей сенсора за останні 120 вимірювань; вісь масштабується параметром стилю. */
@Composable
fun AxisTrace(
    history: List<Vec3>,
    fullScale: Double,
    title: String,
    style: WidgetStyle,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.outline
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            listOf("X", "Y", "Z").forEachIndexed { i, axis ->
                Text(axis, color = style.palette.axes[i], style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val mid = size.height / 2
            if (style.showGrid) {
                listOf(-0.5f, 0.5f).forEach { k ->
                    drawLine(outline.copy(alpha = 0.5f), Offset(0f, mid + k * mid), Offset(size.width, mid + k * mid), 1.dp.toPx(), pathEffect = dash)
                }
            }
            drawLine(outline, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
            if (history.size < 2) return@Canvas
            val selectors = listOf<(Vec3) -> Double>({ it.x }, { it.y }, { it.z })
            selectors.forEachIndexed { axis, select ->
                val path = Path()
                history.forEachIndexed { i, v ->
                    val k = (select(v) / fullScale * style.scale).toFloat().coerceIn(-1f, 1f)
                    val x = size.width * i / (MotionMath.HISTORY_SIZE - 1)
                    val y = mid - k * mid * 0.95f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, style.palette.axes[axis], style = Stroke(width = (style.strokeDp * 0.6f).dp.toPx()))
            }
        }
    }
}
