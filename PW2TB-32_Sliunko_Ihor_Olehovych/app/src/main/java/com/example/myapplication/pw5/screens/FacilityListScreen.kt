package com.example.myapplication.pw5.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.pw5.components.ScreenHeader
import com.example.myapplication.pw5.components.StatusBadge
import com.example.myapplication.pw5.data.Facility
import com.example.myapplication.pw5.data.FacilityCatalog
import com.example.myapplication.pw5.data.ReserveAssessment
import com.example.myapplication.pw5.data.ReserveAssessor

/**
 * Список об'єктів системи аварійного електропостачання.
 *
 * Адаптивність: LazyVerticalGrid з GridCells.Adaptive сам обирає кількість колонок за шириною екрана —
 * одна колонка на телефоні, дві-три на планшеті чи в ландшафті.
 *
 * @param criticalOnly режим розрахунку, отриманий як аргумент маршруту
 * @param onOpenFacility перехід на екран деталей з передачею id об'єкта
 */
@Composable
fun FacilityListScreen(
    criticalOnly: Boolean,
    onOpenFacility: (Int) -> Unit,
    onBack: () -> Unit,
) {
    // Оцінка всіх об'єктів кешується і перераховується лише при зміні режиму
    val assessed = remember(criticalOnly) {
        FacilityCatalog.facilities.map { it to ReserveAssessor.assess(it, criticalOnly) }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 300.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Заголовок займає всю ширину сітки
        item(span = { GridItemSpan(maxLineSpan) }) {
            ScreenHeader(
                title = "Об'єкти та резерв",
                subtitle = "Розрахунок для: " + if (criticalOnly) "критичних споживачів" else "усього навантаження",
                onBack = onBack
            )
        }
        items(assessed, key = { it.first.id }) { (facility, assessment) ->
            FacilityCard(facility, assessment, onClick = { onOpenFacility(facility.id) })
        }
    }
}

/** Картка об'єкта у списку: назва, категорія, автономність та стан готовності. */
@Composable
private fun FacilityCard(facility: Facility, assessment: ReserveAssessment, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    facility.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                StatusBadge(assessment.status, modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                "${facility.category.title} · ${facility.address}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Автономність: ${ReserveAssessor.formatHours(assessment.totalHours)} " +
                    "з ${assessment.requiredHours.toInt()} год",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
