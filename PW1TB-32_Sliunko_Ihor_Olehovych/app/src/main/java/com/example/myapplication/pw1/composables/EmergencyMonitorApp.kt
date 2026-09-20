package com.example.myapplication.pw1.composables

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.pw1.data.EmergencySimulator
import com.example.myapplication.pw1.data.EmergencySnapshot
import com.example.myapplication.pw1.data.PowerSource

/** Тривалість анімації зміни значень при оновленні, мс. */
private const val ANIMATION_MS = 900

/**
 * ПЗ 1, просунутий рівень: моніторинг системи аварійного електропостачання.
 *
 * UI-State:
 *  - [snapshot] — поточні показники; при натисканні кнопки замінюється новим випадковим станом;
 *  - [updatesCount] — лічильник оновлень.
 * Усі числа та кольори не змінюються стрибком, а плавно анімуються (animate*AsState).
 */
@Composable
fun EmergencyMonitorApp(modifier: Modifier = Modifier) {
    var snapshot by remember { mutableStateOf(EmergencySimulator.initial) }
    var updatesCount by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Аварійне електропостачання",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Телеметрія ДБЖ, мережі та дизель-генератора",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SourceStatusCard(snapshot)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AnimatedMetricCard("Напруга мережі", snapshot.gridVoltage, "В", Modifier.weight(1f))
            AnimatedMetricCard("Навантаження", snapshot.loadKw, "кВт", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AnimatedMetricCard("Автономність АКБ", snapshot.autonomyMinutes, "хв", Modifier.weight(1f))
            AnimatedMetricCard("Завантаження ДГУ", snapshot.generatorLoadPercent, "%", Modifier.weight(1f))
        }

        BatteryCard(snapshot.batteryPercent)

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                snapshot = EmergencySimulator.next()
                updatesCount++
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Оновити дані")
        }
        Text(
            text = "Оновлень: $updatesCount",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Картка з активним джерелом живлення; колір та текст змінюються анімовано. */
@Composable
private fun SourceStatusCard(snapshot: EmergencySnapshot) {
    val source = snapshot.source
    val container by animateColorAsState(
        targetValue = when (source) {
            PowerSource.GRID -> MaterialTheme.colorScheme.primaryContainer
            PowerSource.BATTERY -> MaterialTheme.colorScheme.tertiaryContainer
            PowerSource.GENERATOR -> MaterialTheme.colorScheme.errorContainer
        },
        animationSpec = tween(ANIMATION_MS),
        label = "sourceContainer"
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Активне джерело", style = MaterialTheme.typography.labelLarge)
            // Плавна заміна назви джерела: старий текст виїжджає, новий з'являється
            AnimatedContent(
                targetState = source,
                transitionSpec = {
                    (slideInVertically { it / 2 } + fadeIn()) togetherWith
                        (slideOutVertically { -it / 2 } + fadeOut())
                },
                label = "sourceTitle"
            ) { current ->
                Column {
                    Text(
                        text = current.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(current.description, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Картка з числовим показником: значення «докручується» до нового за [ANIMATION_MS]. */
@Composable
private fun AnimatedMetricCard(
    label: String,
    value: Int,
    unit: String,
    modifier: Modifier = Modifier,
) {
    val animatedValue by animateIntAsState(
        targetValue = value,
        animationSpec = tween(ANIMATION_MS),
        label = label
    )
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "$animatedValue $unit",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** Заряд акумуляторів: прогрес-бар із плавною зміною довжини та кольору за рівнем заряду. */
@Composable
private fun BatteryCard(batteryPercent: Int) {
    val progress by animateFloatAsState(
        targetValue = batteryPercent / 100f,
        animationSpec = tween(ANIMATION_MS),
        label = "batteryProgress"
    )
    val percentText by animateIntAsState(
        targetValue = batteryPercent,
        animationSpec = tween(ANIMATION_MS),
        label = "batteryPercent"
    )
    val barColor: Color by animateColorAsState(
        targetValue = when {
            batteryPercent > 50 -> MaterialTheme.colorScheme.primary
            batteryPercent > 25 -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.error
        },
        animationSpec = tween(ANIMATION_MS),
        label = "batteryColor"
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Заряд акумуляторів", style = MaterialTheme.typography.labelMedium)
                Text("$percentText %", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp),
                color = barColor,
                trackColor = MaterialTheme.colorScheme.surface,
            )
        }
    }
}
