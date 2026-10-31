package com.example.myapplication.pw9.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.pw9.data.DataMode
import com.example.myapplication.pw9.domain.FleetSummary
import com.example.myapplication.pw9.domain.FuelCalc
import com.example.myapplication.pw9.domain.FuelTank
import com.example.myapplication.pw9.domain.ReserveStatus
import com.example.myapplication.pw9.domain.TankFilter
import com.example.myapplication.pw9.domain.TankSort
import com.example.myapplication.pw9.viewmodel.SourceState
import com.example.myapplication.ui.common.MessageBanner
import com.example.myapplication.ui.common.MetricTile
import com.example.myapplication.ui.common.StatusBadge
import com.example.myapplication.ui.common.Tone
import com.example.myapplication.ui.common.colors
import com.example.myapplication.ui.common.fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun ReserveStatus.tone() = when (this) {
    ReserveStatus.NORMAL -> Tone.GOOD
    ReserveStatus.WARNING -> Tone.WARN
    ReserveStatus.CRITICAL -> Tone.BAD
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

/** Перемикач джерела даних: Room / сервер / агрегований режим. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSelector(mode: DataMode, onModeChange: (DataMode) -> Unit, modifier: Modifier = Modifier) {
    val items = DataMode.entries
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        items.forEachIndexed { i, item ->
            SegmentedButton(
                selected = item == mode,
                onClick = { onModeChange(item) },
                shape = SegmentedButtonDefaults.itemShape(i, items.size),
                label = { Text(item.label) }
            )
        }
    }
}

/** Рядок стану джерела: звідки дані, коли оновлено, помилка та перемикач «сервер недоступний». */
@Composable
fun SourceStatusBar(
    state: SourceState,
    serverOffline: Boolean,
    onServerOfflineChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Джерело: ${state.origin}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                state.updatedAt?.let {
                    Text("Синхронізовано о ${timeFormat.format(Date(it))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else TextButton(onClick = onRefresh) { Text("Оновити") }
        }
        state.error?.let { MessageBanner(it, Tone.BAD) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Імітувати недоступність сервера", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(checked = serverOffline, onCheckedChange = onServerOfflineChange)
        }
    }
}

/** Плитки агрегованих показників; плитки статусів працюють як фільтр. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SummaryPanel(
    summary: FleetSummary,
    selectedStatus: ReserveStatus?,
    onStatusClick: (ReserveStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Паливо, л", "${summary.totalFuelL.fmt(0)} (${summary.fillPercent.fmt(0)} %)", Modifier.weight(1f))
            MetricTile(
                "Долити до 24 год, л", summary.fuelToDeliverL.fmt(0), Modifier.weight(1f),
                tone = if (summary.fuelToDeliverL > 0) Tone.WARN else Tone.GOOD,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReserveStatus.entries.forEach { s ->
                MetricTile(
                    s.label, (summary.byStatus[s] ?: 0).toString(), Modifier.weight(1f),
                    tone = s.tone(), selected = selectedStatus == s, onClick = { onStatusClick(s) },
                )
            }
        }
        summary.weakest?.let { w ->
            Text(
                "Найменший запас: ${w.name} (${w.facility}) — ${FuelCalc.autonomyHours(w).fmt()} год" +
                    if (summary.pendingSync > 0) "\nНе синхронізовано записів: ${summary.pendingSync}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Пошук, фільтр за статусом (чипи) та меню сортування. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterBar(
    filter: TankFilter,
    onQueryChange: (String) -> Unit,
    onStatusChange: (ReserveStatus?) -> Unit,
    onSortChange: (TankSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sortMenu by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = filter.query,
            onValueChange = onQueryChange,
            label = { Text("Пошук за ДГУ або об'єктом") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
            FilterChip(selected = filter.status == null, onClick = { onStatusChange(null) }, label = { Text("Усі") })
            ReserveStatus.entries.forEach { s ->
                FilterChip(selected = filter.status == s, onClick = { onStatusChange(s) }, label = { Text(s.label) })
            }
            Box {
                TextButton(onClick = { sortMenu = true }) { Text("↕ ${filter.sort.label}") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    TankSort.entries.forEach { s ->
                        DropdownMenuItem(text = { Text(s.label) }, onClick = { onSortChange(s); sortMenu = false })
                    }
                }
            }
        }
    }
}

/** Картка резервуара: рівень палива, автономність, рекомендація та дії. */
@Composable
fun TankCard(
    tank: FuelTank,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val status = FuelCalc.status(tank)
    val barColor = status.tone().colors().second
    val fill by animateFloatAsState((FuelCalc.fillPercent(tank) / 100).toFloat().coerceIn(0f, 1f), label = "fill")
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${tank.name} · ${tank.facility}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${tank.id} · ${tank.ratedPowerKw.fmt(0)} кВт, навантаження ${tank.loadKw.fmt(0)} кВт",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusBadge(status.label, status.tone())
            }
            LinearProgressIndicator(
                progress = { fill },
                color = barColor,
                strokeCap = StrokeCap.Round,
                modifier = Modifier.fillMaxWidth().height(8.dp)
            )
            Text(
                "${tank.levelL.fmt(0)} / ${tank.capacityL.fmt(0)} л (${FuelCalc.fillPercent(tank).fmt(0)} %) · " +
                    "витрата ${FuelCalc.consumptionLph(tank).fmt(2)} л/год · автономність ${FuelCalc.autonomyHours(tank).fmt()} год",
                style = MaterialTheme.typography.bodySmall
            )
            val toNorm = FuelCalc.fuelToNormL(tank)
            if (toNorm > 0) {
                Text(
                    "Долити ${toNorm.fmt(0)} л → ${FuelCalc.autonomyAfterRefuel(tank).fmt()} год",
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = status.tone().colors().second
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (tank.pendingSync) StatusBadge("очікує синхронізації", Tone.NEUTRAL)
                Box(Modifier.weight(1f))
                onEdit?.let { TextButton(onClick = it) { Text("Змінити") } }
                onDelete?.let { TextButton(onClick = it) { Text("Видалити") } }
            }
        }
    }
}
