package com.example.myapplication.pw5.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication.pw5.components.InfoRow
import com.example.myapplication.pw5.components.ScreenHeader
import com.example.myapplication.pw5.components.SectionCard
import com.example.myapplication.pw5.components.StatusBadge
import com.example.myapplication.pw5.data.Facility
import com.example.myapplication.pw5.data.ReserveAssessment
import com.example.myapplication.pw5.data.ReserveAssessor

/**
 * Деталі об'єкта, id якого прийшов як аргумент маршруту.
 *
 * Навігація назад: «До списку» знімає з back stack лише цей екран (popBackStack),
 * «У головне меню» повертає до стартового екрана, прибираючи всі проміжні.
 * Адаптивність: на широкому екрані параметри та оцінка розташовані у двох колонках.
 */
@Composable
fun FacilityDetailsScreen(
    facility: Facility?,
    criticalOnly: Boolean,
    onBack: () -> Unit,
    onBackToMenu: () -> Unit,
) {
    AdaptiveScreen {
        if (facility == null) {
            // Захист від некоректного аргументу (наприклад, deep link на неіснуючий об'єкт)
            ScreenHeader(title = "Об'єкт не знайдено", onBack = onBack)
            return@AdaptiveScreen
        }
        val assessment = remember(facility, criticalOnly) { ReserveAssessor.assess(facility, criticalOnly) }

        ScreenHeader(title = facility.name, subtitle = facility.address, onBack = onBack)
        StatusBadge(assessment.status)

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= WIDE_SCREEN) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    ParametersSection(facility, Modifier.weight(1f))
                    AssessmentSection(assessment, criticalOnly, Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ParametersSection(facility)
                    AssessmentSection(assessment, criticalOnly)
                }
            }
        }

        SectionCard(title = "Рекомендації") {
            assessment.recommendations.forEach { line ->
                Text("• $line", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("До списку") }
            Button(onClick = onBackToMenu, modifier = Modifier.weight(1f)) { Text("У головне меню") }
        }
    }
}

/** Технічні параметри об'єкта. */
@Composable
private fun ParametersSection(facility: Facility, modifier: Modifier = Modifier) {
    SectionCard(title = "Параметри об'єкта", modifier = modifier) {
        InfoRow("Категорія", facility.category.title)
        InfoRow("Повне навантаження", "%.0f кВт".format(facility.totalLoadKw))
        InfoRow("Критичні споживачі", "%.0f кВт".format(facility.criticalLoadKw))
        InfoRow("Ємність АКБ", "%.0f кВт·год".format(facility.batteryKwh))
        InfoRow("Потужність ДГУ", if (facility.generatorKw > 0) "%.0f кВт".format(facility.generatorKw) else "немає")
        InfoRow("Запас палива", "%.0f л".format(facility.fuelLiters))
    }
}

/** Результат розрахунку автономності. */
@Composable
private fun AssessmentSection(assessment: ReserveAssessment, criticalOnly: Boolean, modifier: Modifier = Modifier) {
    SectionCard(title = "Оцінка резерву", modifier = modifier) {
        InfoRow("Режим", if (criticalOnly) "критичні споживачі" else "усе навантаження")
        InfoRow("Розрахункове навантаження", "%.0f кВт".format(assessment.loadKw))
        InfoRow("Робота від АКБ", ReserveAssessor.formatHours(assessment.batteryHours))
        InfoRow(
            "Робота від ДГУ",
            when {
                !assessment.hasGenerator -> "немає ДГУ"
                !assessment.generatorCoversLoad -> "недостатня потужність"
                else -> ReserveAssessor.formatHours(assessment.generatorHours)
            }
        )
        InfoRow("Загальна автономність", ReserveAssessor.formatHours(assessment.totalHours))
        InfoRow("Норматив", "%.0f год".format(assessment.requiredHours))
    }
}
