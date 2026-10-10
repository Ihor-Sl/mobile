package com.example.myapplication.pw5.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.pw5.components.ScreenHeader
import com.example.myapplication.pw5.components.SectionCard
import com.example.myapplication.pw5.components.readinessColors
import com.example.myapplication.pw5.data.FacilityCatalog
import com.example.myapplication.pw5.data.ReadinessStatus
import com.example.myapplication.pw5.data.ReserveAssessor

/**
 * Зведення готовності: кількість об'єктів у кожному стані та «рейтинг» автономності.
 * Об'єкти відсортовано від найменш захищеного; натискання «Деталі» відкриває екран об'єкта.
 */
@Composable
fun ReadinessSummaryScreen(
    criticalOnly: Boolean,
    onOpenFacility: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val summary = remember(criticalOnly) { ReserveAssessor.summarize(FacilityCatalog.facilities, criticalOnly) }
    // Частка виконання нормативу: 1.0 — автономність дорівнює нормативу
    val ranking = remember(criticalOnly) {
        FacilityCatalog.facilities
            .map { it to ReserveAssessor.assess(it, criticalOnly) }
            .sortedBy { (_, a) -> a.totalHours / a.requiredHours }
    }

    AdaptiveScreen {
        ScreenHeader(
            title = "Зведення готовності",
            subtitle = "Розрахунок для: " + if (criticalOnly) "критичних споживачів" else "усього навантаження",
            onBack = onBack
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            CountCard(ReadinessStatus.READY, summary.ready, Modifier.weight(1f))
            CountCard(ReadinessStatus.LIMITED, summary.limited, Modifier.weight(1f))
            CountCard(ReadinessStatus.NOT_READY, summary.notReady, Modifier.weight(1f))
        }
        SectionCard(title = "Виконання нормативу автономності") {
            ranking.forEach { (facility, assessment) ->
                val ratio = (assessment.totalHours / assessment.requiredHours).toFloat()
                Column(Modifier.padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(facility.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onOpenFacility(facility.id) }) { Text("Деталі") }
                    }
                    LinearProgressIndicator(
                        progress = { ratio.coerceIn(0f, 1f) },
                        color = readinessColors(assessment.status).second,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                    )
                    Text(
                        "%.0f %% нормативу · %s".format(ratio * 100, ReserveAssessor.formatHours(assessment.totalHours)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

/** Лічильник об'єктів одного стану готовності. */
@Composable
private fun CountCard(status: ReadinessStatus, count: Int, modifier: Modifier = Modifier) {
    val (background, content) = readinessColors(status)
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = background, contentColor = content)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("$count", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(status.title, style = MaterialTheme.typography.labelMedium)
        }
    }
}
