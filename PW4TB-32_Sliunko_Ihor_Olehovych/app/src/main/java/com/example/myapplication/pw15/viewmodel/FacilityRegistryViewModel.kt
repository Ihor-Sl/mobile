package com.example.myapplication.pw15.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.facilities.Facility
import com.example.myapplication.facilities.FacilityCatalog
import com.example.myapplication.facilities.FacilityQuery
import com.example.myapplication.facilities.PowerStatus
import com.example.myapplication.facilities.applyQuery
import com.example.myapplication.facilities.toggled
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class RegistryUiState(
    val visible: List<Facility> = emptyList(),
    val total: Int = 0,
    val query: FacilityQuery = FacilityQuery(),
    val selectedIds: Set<String> = emptySet(),
    val onlySelected: Boolean = false,
    val selectedPowerKw: Double = 0.0,
    val selectedLoadKw: Double = 0.0,
)

/**
 * ViewModel ПЗ 15. Текст пошуку проходить через debounce, щоб не фільтрувати список на кожен символ;
 * очищення поля застосовується одразу. Підсумок рахується за всіма вибраними об'єктами, навіть прихованими фільтром.
 */
@OptIn(FlowPreview::class)
class FacilityRegistryViewModel(
    initial: List<Facility> = FacilityCatalog.sample(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val facilities = MutableStateFlow(initial)
    private val filters = MutableStateFlow(FacilityQuery())
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val onlySelected = MutableStateFlow(false)

    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    private val appliedText = _searchText.debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }

    val uiState: StateFlow<RegistryUiState> =
        combine(facilities, filters, appliedText, selectedIds, onlySelected) { items, filter, text, selected, only ->
            val query = filter.copy(text = text)
            val matched = items.applyQuery(query)
            val chosen = items.filter { it.id in selected }
            RegistryUiState(
                visible = if (only) matched.filter { it.id in selected } else matched,
                total = items.size,
                query = query,
                selectedIds = selected,
                onlySelected = only,
                selectedPowerKw = chosen.sumOf { it.reserveKw },
                selectedLoadKw = chosen.sumOf { it.loadKw },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RegistryUiState(visible = initial.applyQuery(FacilityQuery()), total = initial.size))

    fun onSearchChange(text: String) { _searchText.value = text }

    fun onQueryChange(query: FacilityQuery) { filters.value = query }

    fun toggleSelected(id: String) { selectedIds.update { it.toggled(id) } }

    fun selectVisible() { selectedIds.update { it + uiState.value.visible.map(Facility::id) } }

    fun clearSelection() {
        selectedIds.value = emptySet()
        onlySelected.value = false
    }

    fun toggleOnlySelected() { onlySelected.update { !it } }

    /** Групова дія: змінює статус вибраних об'єктів і час їх оновлення. */
    fun setStatusForSelected(status: PowerStatus) {
        val selected = selectedIds.value
        val now = clock()
        facilities.update { list ->
            list.map { if (it.id in selected) it.copy(status = status, updatedAt = now) else it }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
