package com.example.myapplication.pw7.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.pw7.domain.Alarm
import com.example.myapplication.pw7.domain.AlarmLevel

/** Кольори рівня тривоги (фон, текст) — однакові у світлій і темній темах. */
fun levelColors(level: AlarmLevel): Pair<Color, Color> = when (level) {
    AlarmLevel.NORMAL -> Color(0xFFD6F5DD) to Color(0xFF0B5D1E)
    AlarmLevel.WARNING -> Color(0xFFFFF0C2) to Color(0xFF6B4E00)
    AlarmLevel.CRITICAL -> Color(0xFFFFDAD6) to Color(0xFF93000A)
}

/**
 * Універсальний контейнер трьох станів екрана: завантаження, помилка (з кнопкою повтору) та вміст.
 * Перевикористовується для будь-яких асинхронних даних — View лише відображає стан, отриманий з ViewModel.
 */
@Composable
fun StateContainer(
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            isLoading -> Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Завантаження даних…", modifier = Modifier.padding(top = 8.dp))
                }
            }
            else -> {
                if (errorMessage != null) ErrorBanner(errorMessage, onRetry)
                content()
            }
        }
    }
}

/** Повідомлення про помилку з кнопкою «Повторити». */
@Composable
fun ErrorBanner(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Помилка", fontWeight = FontWeight.Bold)
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onClick = onRetry) { Text("Повторити") }
        }
    }
}

/** Віджет показника: назва, значення та колір рівня тривоги (плавно змінюється). */
@Composable
fun MetricCard(label: String, value: String, level: AlarmLevel, modifier: Modifier = Modifier) {
    val (targetBackground, targetContent) = levelColors(level)
    val background by animateColorAsState(targetBackground, label = "metricBackground")
    val content by animateColorAsState(targetContent, label = "metricContent")
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = background, contentColor = content)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Загальний стан системи; текст змінюється з анімацією. */
@Composable
fun OverallStatusCard(level: AlarmLevel, subtitle: String, modifier: Modifier = Modifier) {
    val (targetBackground, content) = levelColors(level)
    val background by animateColorAsState(targetBackground, label = "overallBackground")
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = background, contentColor = content)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Стан системи", style = MaterialTheme.typography.labelLarge)
            AnimatedContent(targetState = level, label = "overallLevel") { current ->
                Text(current.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Перелік активних тривог. */
@Composable
fun AlarmList(alarms: List<Alarm>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Тривоги", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (alarms.isEmpty()) {
            Text("Активних тривог немає", style = MaterialTheme.typography.bodyMedium)
        }
        alarms.forEach { alarm ->
            val (_, color) = levelColors(alarm.level)
            Text("● ${alarm.message}", color = color, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Рядок кроку тесту АВР: ✓ — виконано, індикатор — поточний, ○ — очікує, ✕ — відмова. */
enum class StepStatus { DONE, ACTIVE, PENDING, FAILED }

@Composable
fun StepRow(title: String, status: StepStatus, detail: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 3.dp)
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (status) {
                StepStatus.DONE -> Text("✓", color = Color(0xFF0B8A2E), fontWeight = FontWeight.Bold)
                StepStatus.ACTIVE -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                StepStatus.PENDING -> Text("○", color = MaterialTheme.colorScheme.outline)
                StepStatus.FAILED -> Text("✕", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.padding(start = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (status == StepStatus.ACTIVE) FontWeight.SemiBold else FontWeight.Normal,
                color = if (status == StepStatus.PENDING) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
            )
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
