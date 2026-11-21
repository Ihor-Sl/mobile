package com.example.myapplication.pw14.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.facilities.Facility
import com.example.myapplication.facilities.FacilityCatalog
import com.example.myapplication.facilities.FacilityQuery
import com.example.myapplication.facilities.Readiness
import com.example.myapplication.facilities.SortKey
import com.example.myapplication.facilities.SortRule
import com.example.myapplication.facilities.applyQuery
import com.example.myapplication.facilities.toggled
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class FacilityMapUiState(
    val visible: List<Facility> = emptyList(),
    val selectedId: String? = null,
    val readiness: Set<Readiness> = emptySet(),
    val sortByAutonomy: Boolean = false,
)

/**
 * ViewModel ПЗ 14 — єдине джерело стану вибору для списку й мапи.
 * Якщо вибраний об'єкт зник після фільтрації, вибір скидається, щоб не лишити «завислий» маркер.
 */
class FacilityMapViewModel(private val all: List<Facility> = FacilityCatalog.sample()) : ViewModel() {
    private val selectedId = MutableStateFlow<String?>(null)
    private val readiness = MutableStateFlow<Set<Readiness>>(emptySet())
    private val sortByAutonomy = MutableStateFlow(false)

    val uiState: StateFlow<FacilityMapUiState> =
        combine(selectedId, readiness, sortByAutonomy) { selected, filter, byAutonomy ->
            val sort = if (byAutonomy) SortRule(SortKey.AUTONOMY) else SortRule(SortKey.NAME)
            val visible = all.applyQuery(FacilityQuery(readiness = filter, primary = sort))
            FacilityMapUiState(visible, selected?.takeIf { id -> visible.any { it.id == id } }, filter, byAutonomy)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, FacilityMapUiState(visible = all.applyQuery(FacilityQuery())))

    fun facility(id: String?): Facility? = all.firstOrNull { it.id == id }

    /** Одна дія і для картки, і для маркера: повторне натискання картки знімає вибір. */
    fun select(id: String) { selectedId.value = id }

    fun toggleCard(id: String) { selectedId.update { if (it == id) null else id } }

    fun toggleReadiness(value: Readiness) { readiness.update { it.toggled(value) } }

    fun toggleSort() { sortByAutonomy.update { !it } }
}
