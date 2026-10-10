package com.example.myapplication.pw8.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.pw8.data.local.EventType
import com.example.myapplication.pw8.data.local.EventWithSource
import com.example.myapplication.pw8.data.local.SourceKind
import com.example.myapplication.pw8.data.local.SourceStats
import com.example.myapplication.pw8.domain.EventFilter
import com.example.myapplication.pw8.domain.EventSort
import com.example.myapplication.pw8.domain.EventValidator
import com.example.myapplication.pw8.domain.LogSummary

/** Кольори типу події (фон, текст). */
private fun typeColors(type: EventType): Pair<Color, Color> = when (type) {
    EventType.OUTAGE -> Color(0xFFFFDAD6) to Color(0xFF93000A)
    EventType.TEST -> Color(0xFFD6E3FF) to Color(0xFF0B3D91)
    EventType.MAINTENANCE -> Color(0xFFFFF0C2) to Color(0xFF6B4E00)
}

/** Плашка типу події. */
@Composable
fun TypeBadge(type: EventType, modifier: Modifier = Modifier) {
    val (background, content) = typeColors(type)
    Surface(color = background, contentColor = content, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(
            type.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}

/**
 * Картка статистики джерела (агрегати з SQL-запиту GROUP BY).
 * Натискання вмикає / вимикає фільтр журналу за цим джерелом.
 */
@Composable
fun StatCard(stats: SourceStats, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stats.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "${stats.kind.title} · %.0f кВт".format(stats.ratedPowerKw),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Подій: ${stats.eventCount} (аварій ${stats.outageCount})", style = MaterialTheme.typography.bodySmall)
            Text("Напрацювання: %.1f год".format(stats.totalHours), style = MaterialTheme.typography.bodySmall)
            Text("Енергія: %.0f кВт·год".format(stats.totalEnergyKwh), style = MaterialTheme.typography.bodySmall)
            if (stats.kind == SourceKind.GENERATOR) {
                Text("Паливо: %.0f л".format(stats.totalFuelL), style = MaterialTheme.typography.bodySmall)
            }
            Text("Сер. завантаження: %.0f %%".format(stats.avgLoadPercent), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Пошук, фільтр за типом та вибір сортування. */
@Composable
fun FilterBar(
    filter: EventFilter,
    onQueryChange: (String) -> Unit,
    onTypeChange: (EventType?) -> Unit,
    onSortChange: (EventSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = filter.query,
            onValueChange = onQueryChange,
            label = { Text("Пошук за джерелом або приміткою") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = filter.type == null, onClick = { onTypeChange(null) }, label = { Text("Усі") })
            EventType.entries.forEach { type ->
                FilterChip(
                    selected = filter.type == type,
                    onClick = { onTypeChange(if (filter.type == type) null else type) },
                    label = { Text(type.title) }
                )
            }
        }
        SortSelector(filter.sort, onSortChange)
    }
}

/** Вибір сортування у випадаючому меню. */
@Composable
private fun SortSelector(sort: EventSort, onSortChange: (EventSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Сортування: ${sort.title} ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            EventSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.title) },
                    onClick = {
                        onSortChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** Підсумок по відфільтрованих подіях. */
@Composable
fun SummaryLine(summary: LogSummary, modifier: Modifier = Modifier) {
    Text(
        "Подій: ${summary.count} · %.1f год · %.0f кВт·год · паливо %.0f л"
            .format(summary.totalHours, summary.totalEnergyKwh, summary.totalFuelL),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
    )
}

/** Картка події журналу з діями «Змінити» та «Видалити». */
@Composable
fun EventCard(item: EventWithSource, onEdit: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val e = item.event
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(e.type)
                Text(
                    EventValidator.formatDateTime(e.startedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                )
                if (e.remoteId != null) {
                    Text("SCADA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            Text(
                item.sourceName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                "%d хв · %.0f кВт (%.0f %% номіналу) · %.1f кВт·год%s".format(
                    e.durationMin, e.avgLoadKw, item.loadPercent, item.energyKwh,
                    if (item.sourceKind == SourceKind.GENERATOR) " · %.1f л".format(e.fuelUsedL) else ""
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            if (e.note.isNotBlank()) {
                Text(e.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onEdit) { Text("Змінити") }
                TextButton(onClick = onDelete) { Text("Видалити", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
