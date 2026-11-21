package com.example.myapplication.pw15.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.facilities.PowerStatus
import com.example.myapplication.facilities.SortKey
import com.example.myapplication.facilities.ui.FacilityCard
import com.example.myapplication.facilities.ui.FacilityFilterPanel
import com.example.myapplication.pw15.viewmodel.FacilityRegistryViewModel
import com.example.myapplication.pw15.viewmodel.RegistryUiState
import com.example.myapplication.ui.common.MessageBanner
import com.example.myapplication.ui.common.SectionCard
import com.example.myapplication.ui.common.Tone
import com.example.myapplication.ui.common.fmt

private val REGISTRY_SORT_KEYS = listOf(SortKey.NAME, SortKey.TYPE, SortKey.POWER, SortKey.AUTONOMY, SortKey.UPDATED)

/** Екран ПЗ 15: пошук, багаторівнева фільтрація, два рівні сортування та вибір елементів. */
@Composable
fun FacilityRegistryScreen(viewModel: FacilityRegistryViewModel = viewModel { FacilityRegistryViewModel() }) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val searchText by viewModel.searchText.collectAsStateWithLifecycle()
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp

        // Панель фільтрів — перший елемент списку, тому після відкриття чи закриття показуємо початок
        LaunchedEffect(filtersOpen) {
            listState.scrollToItem(0)
        }
        // LazyColumn тримається за елемент із тим самим key, тому новий порядок показується з початку;
        // відкрита в списку панель не повинна «стрибати» під час натискання чипів
        LaunchedEffect(state.query) {
            if (wide || !filtersOpen) listState.scrollToItem(0)
        }
        val filterPanel: @Composable () -> Unit = {
            FacilityFilterPanel(state.query, viewModel::onQueryChange, REGISTRY_SORT_KEYS)
        }
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                Column(Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    filterPanel()
                }
            }
            Column(Modifier.weight(0.58f).fillMaxHeight()) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = viewModel::onSearchChange,
                    label = { Text("Пошук за назвою або районом") },
                    singleLine = true,
                    trailingIcon = {
                        if (searchText.isNotEmpty()) TextButton(onClick = { viewModel.onSearchChange("") }) { Text("✕") }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Показано ${state.visible.size} з ${state.total}",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (!wide) {
                        val active = state.query.activeFilters
                        TextButton(onClick = { filtersOpen = !filtersOpen }) {
                            Text((if (filtersOpen) "Сховати фільтри" else "Фільтри") + if (active > 0) " ($active)" else "")
                        }
                    }
                }
                if (state.selectedIds.isNotEmpty()) {
                    SelectionBar(
                        state = state,
                        onSelectVisible = viewModel::selectVisible,
                        onOnlySelected = viewModel::toggleOnlySelected,
                        onClear = viewModel::clearSelection,
                        onStatus = viewModel::setStatusForSelected,
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!wide && filtersOpen) {
                        item(key = "filters") { SectionCard { filterPanel() } }
                    }
                    if (state.visible.isEmpty()) {
                        item(key = "empty") { MessageBanner("За цими умовами об'єктів не знайдено", Tone.NEUTRAL) }
                    }
                    items(state.visible, key = { it.id }) { facility ->
                        val selected = facility.id in state.selectedIds
                        FacilityCard(
                            facility = facility,
                            selected = selected,
                            onClick = { viewModel.toggleSelected(facility.id) },
                            leading = {
                                Checkbox(checked = selected, onCheckedChange = { viewModel.toggleSelected(facility.id) })
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Підсумок вибраних об'єктів і групові дії. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionBar(
    state: RegistryUiState,
    onSelectVisible: () -> Unit,
    onOnlySelected: () -> Unit,
    onClear: () -> Unit,
    onStatus: (PowerStatus) -> Unit,
) {
    SectionCard(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(
            "Вибрано ${state.selectedIds.size}: резерв ${state.selectedPowerKw.fmt()} кВт, навантаження ${state.selectedLoadKw.fmt()} кВт",
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(state.onlySelected, onOnlySelected, { Text("Лише вибрані") })
            FilterChip(false, onSelectVisible, { Text("Вибрати всі") })
            FilterChip(false, { onStatus(PowerStatus.RESERVE) }, { Text("На резерв") })
            FilterChip(false, { onStatus(PowerStatus.GRID) }, { Text("На мережу") })
            FilterChip(false, onClear, { Text("Скасувати") })
        }
    }
}
