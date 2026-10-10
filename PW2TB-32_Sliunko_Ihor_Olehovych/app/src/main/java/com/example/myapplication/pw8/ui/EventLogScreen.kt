package com.example.myapplication.pw8.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.pw8.data.local.EventWithSource
import com.example.myapplication.pw8.data.local.SourceStats
import com.example.myapplication.pw8.domain.EventDraft
import com.example.myapplication.pw8.domain.EventFilter
import com.example.myapplication.pw8.domain.EventValidator
import com.example.myapplication.pw8.domain.LogSummary
import com.example.myapplication.pw8.ui.components.EventCard
import com.example.myapplication.pw8.ui.components.EventEditorDialog
import com.example.myapplication.pw8.ui.components.FilterBar
import com.example.myapplication.pw8.ui.components.StatCard
import com.example.myapplication.pw8.ui.components.SummaryLine
import com.example.myapplication.pw8.viewmodel.EventLogViewModel
import com.example.myapplication.pw8.viewmodel.SyncState

private val WIDE_SCREEN = 720.dp

/**
 * ПЗ 8, просунутий рівень: журнал аварійних подій на основі Room.
 *
 * Потік даних: Room (Flow) → Repository → ViewModel (StateFlow) → цей екран.
 * Екран лише відображає списки та передає дії користувача у ViewModel; після запису в БД
 * Room сам надсилає оновлений список, тому окремого «перезавантаження» немає.
 *
 * Адаптивність: на широкому екрані ліворуч статистика й фільтри, праворуч — список подій;
 * на вузькому все розміщено в одному прокручуваному списку, а статистика гортається горизонтально.
 */
@Composable
fun EventLogScreen(viewModel: EventLogViewModel = viewModel(factory = EventLogViewModel.Factory)) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    // Подія, що редагується (null — діалог закрито)
    var editing by remember { mutableStateOf<EventDraft?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Одноразові повідомлення від ViewModel показуються у snackbar; «Скасувати» повертає видалену подію
    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = current.text,
            actionLabel = if (current.undoEvent != null) "Скасувати" else null,
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) current.undoEvent?.let(viewModel::undoDelete)
        viewModel.messageShown()
    }

    val onAdd = {
        editing = EventDraft(
            sourceId = filter.sourceId ?: sources.firstOrNull()?.id,
            dateTime = EventValidator.formatDateTime(System.currentTimeMillis()),
        )
    }

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= WIDE_SCREEN) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Header(syncState, onAdd = onAdd, onSync = { viewModel.sync() })
                        stats.forEach { s ->
                            StatCard(s, s.sourceId == filter.sourceId, { viewModel.toggleSource(s.sourceId) }, Modifier.fillMaxWidth())
                        }
                        FilterBar(filter, viewModel::setQuery, viewModel::setType, viewModel::setSort)
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1.4f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        eventItems(events, summary, filter, viewModel, onEdit = { editing = it })
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { Header(syncState, onAdd = onAdd, onSync = { viewModel.sync() }) }
                    item { StatsRow(stats, filter.sourceId, viewModel::toggleSource) }
                    item { FilterBar(filter, viewModel::setQuery, viewModel::setType, viewModel::setSort) }
                    eventItems(events, summary, filter, viewModel, onEdit = { editing = it })
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }

    editing?.let { draft ->
        EventEditorDialog(
            initial = draft,
            sources = sources,
            onSave = { viewModel.save(it) },
            onDismiss = { editing = null }
        )
    }
}

/** Заголовок екрана, кнопки дій і стан синхронізації. */
@Composable
private fun Header(syncState: SyncState, onAdd: () -> Unit, onSync: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Журнал аварійних подій",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onAdd) { Text("Додати подію") }
            OutlinedButton(onClick = onSync, enabled = syncState != SyncState.Syncing) {
                if (syncState == SyncState.Syncing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Синхронізувати зі SCADA")
            }
        }
        when (syncState) {
            is SyncState.Done -> Text(syncState.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            is SyncState.Error -> Text(syncState.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            SyncState.Syncing -> Text("Завантаження подій зі SCADA…", style = MaterialTheme.typography.bodySmall)
            SyncState.Idle -> Unit
        }
    }
}

/** Горизонтальний ряд карток статистики (для вузького екрана). */
@Composable
private fun StatsRow(stats: List<SourceStats>, selectedId: Long?, onToggle: (Long) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(stats, key = { it.sourceId }) { s ->
            StatCard(s, s.sourceId == selectedId, { onToggle(s.sourceId) }, Modifier.width(220.dp))
        }
    }
}

/** Підсумок і список подій (спільна частина для обох макетів). */
private fun LazyListScope.eventItems(
    events: List<EventWithSource>,
    summary: LogSummary,
    filter: EventFilter,
    viewModel: EventLogViewModel,
    onEdit: (EventDraft) -> Unit,
) {
    item { SummaryLine(summary, Modifier.padding(vertical = 4.dp)) }
    if (events.isEmpty()) {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Подій не знайдено", style = MaterialTheme.typography.titleMedium)
                if (filter != EventFilter(sort = filter.sort)) {
                    TextButton(onClick = viewModel::clearFilters) { Text("Скинути фільтри") }
                }
            }
        }
    }
    items(events, key = { it.event.id }) { item ->
        EventCard(
            item = item,
            onEdit = { onEdit(EventValidator.fromEntity(item.event)) },
            onDelete = { viewModel.delete(item.event) },
            modifier = Modifier.animateItem()
        )
    }
}
