package com.example.myapplication.facilities.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.facilities.Facility
import com.example.myapplication.facilities.FacilityQuery
import com.example.myapplication.facilities.FacilityType
import com.example.myapplication.facilities.PowerStatus
import com.example.myapplication.facilities.Readiness
import com.example.myapplication.facilities.SortKey
import com.example.myapplication.facilities.SortRule
import com.example.myapplication.facilities.toggled
import com.example.myapplication.ui.common.StatusBadge
import com.example.myapplication.ui.common.Tone
import com.example.myapplication.ui.common.fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Readiness.tone(): Tone = when (this) {
    Readiness.READY -> Tone.GOOD
    Readiness.LIMITED -> Tone.WARN
    Readiness.CRITICAL -> Tone.BAD
}

fun PowerStatus.tone(): Tone = when (this) {
    PowerStatus.GRID -> Tone.GOOD
    PowerStatus.RESERVE -> Tone.WARN
    PowerStatus.SERVICE -> Tone.NEUTRAL
    PowerStatus.FAULT -> Tone.BAD
}

fun formatUpdated(timestamp: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(timestamp))

/** Картка об'єкта; вибрана картка виділяється кольором і рамкою. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FacilityCard(
    facility: Facility,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    distanceKm: Double? = null,
    leading: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        facility.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StatusBadge(facility.readiness.label, facility.readiness.tone())
                }
                Text(
                    "${facility.type.label} · ${facility.address}" + (distanceKm?.let { " · ${it.fmt()} км" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(facility.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusBadge(facility.status.label, facility.status.tone())
                    StatusBadge("${facility.reserveKw.fmt()} кВт", Tone.NEUTRAL)
                    StatusBadge("${facility.autonomyHours.fmt()} год", Tone.NEUTRAL)
                    StatusBadge(formatUpdated(facility.updatedAt), Tone.NEUTRAL)
                }
                footer?.invoke()
            }
        }
    }
}

/** Панель багаторівневих фільтрів і двох рівнів сортування. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FacilityFilterPanel(
    query: FacilityQuery,
    onQueryChange: (FacilityQuery) -> Unit,
    sortKeys: List<SortKey>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FilterLabel("Тип")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FacilityType.entries.forEach { item ->
                FilterChip(item in query.types, { onQueryChange(query.copy(types = query.types.toggled(item))) }, { Text(item.label) })
            }
        }
        FilterLabel("Статус")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PowerStatus.entries.forEach { item ->
                FilterChip(item in query.statuses, { onQueryChange(query.copy(statuses = query.statuses.toggled(item))) }, { Text(item.label) })
            }
        }
        FilterLabel("Готовність")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Readiness.entries.forEach { item ->
                FilterChip(item in query.readiness, { onQueryChange(query.copy(readiness = query.readiness.toggled(item))) }, { Text(item.label) })
            }
        }
        FilterLabel("Потужність резерву, від")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.0, 50.0, 100.0, 150.0, 200.0).forEach { value ->
                FilterChip(query.minPowerKw == value, { onQueryChange(query.copy(minPowerKw = value)) }, { Text(if (value == 0.0) "Усі" else "${value.fmt()} кВт") })
            }
        }
        FilterLabel("Сортування: спочатку за")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            sortKeys.forEach { key ->
                val active = query.primary.key == key
                FilterChip(
                    selected = active,
                    onClick = {
                        // Повторне натискання змінює напрямок сортування
                        val rule = if (active) query.primary.copy(descending = !query.primary.descending) else SortRule(key)
                        onQueryChange(query.copy(primary = rule, secondary = query.secondary?.takeIf { it.key != key }))
                    },
                    label = { Text(key.label + if (active) (if (query.primary.descending) " ↓" else " ↑") else "") },
                )
            }
        }
        FilterLabel("Потім за")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(query.secondary == null, { onQueryChange(query.copy(secondary = null)) }, { Text("Немає") })
            sortKeys.filter { it != query.primary.key }.forEach { key ->
                val active = query.secondary?.key == key
                FilterChip(
                    selected = active,
                    onClick = {
                        val rule = if (active) query.secondary.copy(descending = !query.secondary.descending) else SortRule(key)
                        onQueryChange(query.copy(secondary = rule))
                    },
                    label = { Text(key.label + if (active) (if (query.secondary.descending) " ↓" else " ↑") else "") },
                )
            }
        }
        TextButton(onClick = { onQueryChange(FacilityQuery(text = query.text, primary = sortKeys.first().let(::SortRule))) }) {
            Text("Скинути фільтри")
        }
    }
}

@Composable
private fun FilterLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Адаптивне розміщення: на телефоні мапа над списком, у ландшафті — поруч. */
@Composable
fun MapListLayout(
    modifier: Modifier = Modifier,
    map: @Composable (Modifier) -> Unit,
    list: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize()) {
                map(Modifier.weight(1f).fillMaxSize())
                list(Modifier.weight(1f).fillMaxSize())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                map(Modifier.weight(0.42f).fillMaxWidth())
                list(Modifier.weight(0.58f).fillMaxWidth())
            }
        }
    }
}
