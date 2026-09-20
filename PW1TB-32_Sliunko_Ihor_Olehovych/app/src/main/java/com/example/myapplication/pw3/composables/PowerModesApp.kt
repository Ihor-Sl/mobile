package com.example.myapplication.pw3.composables

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.R
import com.example.myapplication.pw3.data.AlertLevel
import com.example.myapplication.pw3.data.ModeReport
import com.example.myapplication.pw3.data.PowerMode
import com.example.myapplication.pw3.data.PowerModeAdvisor

/** Ширина, з якої екран перебудовується у двоколонковий макет (планшет / ландшафт). */
private val WIDE_LAYOUT_MIN_WIDTH = 600.dp

/**
 * ПЗ 3, просунутий рівень: режими роботи системи аварійного електропостачання.
 *
 * UI-State:
 *  - [mode] — обраний режим (змінюється кнопками);
 *  - [loadKw] — навантаження (змінюється слайдером).
 * [report] — похідне значення: перераховується лише коли змінюється mode або loadKw.
 *
 * Адаптивність: BoxWithConstraints вибирає макет за доступною шириною —
 * на вузьких екранах усе йде в одну колонку, на широких — керування ліворуч, показники праворуч.
 */
@Composable
fun PowerModesApp(modifier: Modifier = Modifier) {
    var mode by rememberSaveable { mutableStateOf(PowerMode.GRID) }
    var loadKw by rememberSaveable { mutableFloatStateOf(15f) }
    val report = remember(mode, loadKw) { PowerModeAdvisor.evaluate(mode, loadKw.toDouble()) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (maxWidth >= WIDE_LAYOUT_MIN_WIDTH) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ModeHeader(mode, report.level)
                    ModeSelector(mode, onModeSelected = { mode = it })
                    LoadSlider(loadKw, onLoadChange = { loadKw = it })
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ReportSection(mode, report)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ModeHeader(mode, report.level)
                ModeSelector(mode, onModeSelected = { mode = it })
                LoadSlider(loadKw, onLoadChange = { loadKw = it })
                ReportSection(mode, report)
            }
        }
    }
}

/** Заголовок із зображенням джерела живлення та назвою режиму. */
@Composable
private fun ModeHeader(mode: PowerMode, level: AlertLevel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PowerIllustration(level)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Режими живлення",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Поточний режим: ${mode.title}",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

/**
 * Зображення рівня стану режиму: зелена галочка (норма), жовтий знак (увага), червоний знак (перевантаження).
 * Файли лежать у res/drawable; при зміні рівня одна картинка плавно змінюється іншою (Crossfade).
 * Усі зображення мають білий фон, тому вони розміщені на білій закругленій плашці —
 * так вони однаково виглядають і у світлій, і в темній темі.
 */
@Composable
private fun PowerIllustration(level: AlertLevel, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(6.dp)
    ) {
        Crossfade(targetState = level, label = "alertImage") { current ->
            Image(
                painter = painterResource(alertImageRes(current)),
                contentDescription = alertDescription(current),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** Відповідність рівня стану та зображення з res/drawable. */
@DrawableRes
private fun alertImageRes(level: AlertLevel): Int = when (level) {
    AlertLevel.OK -> R.drawable.green_alert
    AlertLevel.WARNING -> R.drawable.yellow_alert
    AlertLevel.CRITICAL -> R.drawable.red_alert
}

/** Опис зображення для екранних читалок (accessibility). */
private fun alertDescription(level: AlertLevel): String = when (level) {
    AlertLevel.OK -> "Норма"
    AlertLevel.WARNING -> "Увага"
    AlertLevel.CRITICAL -> "Перевантаження"
}

/** Кнопки вибору режиму: обраний — Button, інші — OutlinedButton; розкладка 2×2. */
@Composable
private fun ModeSelector(selected: PowerMode, onModeSelected: (PowerMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PowerMode.entries.chunked(2).forEach { rowModes ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowModes.forEach { item ->
                    val modifier = Modifier.weight(1f)
                    if (item == selected) {
                        Button(onClick = { onModeSelected(item) }, modifier = modifier) { Text(item.title, maxLines = 2) }
                    } else {
                        OutlinedButton(onClick = { onModeSelected(item) }, modifier = modifier) { Text(item.title, maxLines = 2) }
                    }
                }
            }
        }
    }
}

/** Слайдер навантаження 1…40 кВт із поточним значенням у підписі. */
@Composable
private fun LoadSlider(loadKw: Float, onLoadChange: (Float) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Навантаження: %.0f кВт".format(loadKw), style = MaterialTheme.typography.titleMedium)
            Slider(value = loadKw, onValueChange = onLoadChange, valueRange = 1f..40f)
        }
    }
}

/** Блок інформаційних карток та коментаря для обраного режиму. */
@Composable
private fun ReportSection(mode: PowerMode, report: ModeReport) {
    StatusCard(mode, report)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoCard("Віддається", "%.1f кВт".format(report.suppliedKw), Modifier.weight(1f))
        InfoCard("Покриття", "%.0f %%".format(report.coveragePercent), Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoCard("Завантаження", "%.0f %%".format(report.utilizationPercent), Modifier.weight(1f))
        InfoCard("Перехід", PowerModeAdvisor.formatTransfer(report.transferSeconds), Modifier.weight(1f))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Опис режиму", style = MaterialTheme.typography.labelLarge)
            Text(mode.description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Головна картка: джерело, рівень стану та коментар; колір залежить від [AlertLevel]. */
@Composable
private fun StatusCard(mode: PowerMode, report: ModeReport) {
    val container by animateColorAsState(levelContainerColor(report.level), label = "statusContainer")
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Джерело живлення", style = MaterialTheme.typography.labelLarge)
            Text(mode.sourceName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                text = when (report.level) {
                    AlertLevel.OK -> "Стан: норма"
                    AlertLevel.WARNING -> "Стан: увага"
                    AlertLevel.CRITICAL -> "Стан: перевантаження"
                },
                style = MaterialTheme.typography.titleSmall
            )
            Text(report.comment, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Мала інформаційна картка «назва — значення». */
@Composable
private fun InfoCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun levelContainerColor(level: AlertLevel): Color = when (level) {
    AlertLevel.OK -> MaterialTheme.colorScheme.primaryContainer
    AlertLevel.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
    AlertLevel.CRITICAL -> MaterialTheme.colorScheme.errorContainer
}
