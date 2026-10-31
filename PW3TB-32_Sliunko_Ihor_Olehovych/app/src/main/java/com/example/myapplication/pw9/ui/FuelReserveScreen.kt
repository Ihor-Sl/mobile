package com.example.myapplication.pw9.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.pw9.data.DataMode
import com.example.myapplication.pw9.domain.FuelTank
import com.example.myapplication.pw9.domain.TankDraft
import com.example.myapplication.pw9.ui.components.FilterBar
import com.example.myapplication.pw9.ui.components.ModeSelector
import com.example.myapplication.pw9.ui.components.SourceStatusBar
import com.example.myapplication.pw9.ui.components.SummaryPanel
import com.example.myapplication.pw9.ui.components.TankCard
import com.example.myapplication.pw9.ui.components.TankEditorDialog
import com.example.myapplication.pw9.viewmodel.FuelReserveViewModel
import com.example.myapplication.ui.common.ScreenTitle

private val WIDE_SCREEN = 720.dp

/**
 * ПЗ 9, просунутий рівень: резерв палива ДГУ через Repository та два Data Source.
 *
 * Потік даних: Room / Retrofit → Repository → ViewModel (StateFlow) → цей екран.
 * Екран лише відображає стан і передає дії користувача у ViewModel.
 * Адаптивність: на широкому екрані ліворуч панель керування (режим, показники, фільтри),
 * праворуч — список; на вузькому все в одному LazyColumn.
 */
@Composable
fun FuelReserveScreen(viewModel: FuelReserveViewModel = viewModel(factory = FuelReserveViewModel.Factory)) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val serverOffline by viewModel.serverOffline.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<TankDraft?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(m.text, actionLabel = if (m.undo != null) "Скасувати" else null, duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) m.undo?.let(viewModel::undoDelete)
        viewModel.messageShown()
    }

    // Панель керування однакова для обох макетів
    val controls: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenTitle("Резерв палива ДГУ", "Repository об'єднує Room і сервер диспетчерської")
            ModeSelector(mode, viewModel::setMode)
            SourceStatusBar(source, serverOffline, viewModel::setServerOffline, viewModel::refresh)
            SummaryPanel(summary, filter.status, viewModel::toggleStatus)
            FilterBar(filter, viewModel::setQuery, viewModel::toggleStatus, viewModel::setSort)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Показано ${visible.size} з ${summary.count}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Button(onClick = { editing = TankDraft() }) { Text("+ Резервуар") }
            }
        }
    }
    val editAllowed = mode != DataMode.REMOTE || !serverOffline
    val list: LazyListScope.() -> Unit = {
        items(visible, key = FuelTank::id) { tank ->
            TankCard(
                tank,
                onEdit = if (editAllowed) ({ editing = TankDraft.of(tank) }) else null,
                onDelete = if (editAllowed) ({ viewModel.delete(tank) }) else null,
                modifier = Modifier.animateItem()
            )
        }
        if (visible.isEmpty()) {
            item { Text("Немає записів, що відповідають фільтру", modifier = Modifier.padding(16.dp)) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= WIDE_SCREEN) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)
                    ) { controls() }
                    LazyColumn(
                        modifier = Modifier.weight(1.3f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        content = list
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item { controls() }
                    list()
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
    }

    editing?.let { draft ->
        TankEditorDialog(initial = draft, onDismiss = { editing = null }, onSave = viewModel::save)
    }
}
