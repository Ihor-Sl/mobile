package com.example.myapplication.pw10.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myapplication.pw10.data.DataOrigin
import com.example.myapplication.pw10.data.GridRepository
import com.example.myapplication.pw10.data.remote.MockGridServer
import com.example.myapplication.pw10.data.remote.NetworkProfile
import com.example.myapplication.pw10.domain.FacilityForecast
import com.example.myapplication.pw10.domain.ForecastFilter
import com.example.myapplication.pw10.domain.ForecastSort
import com.example.myapplication.pw10.domain.OutagePlanner
import com.example.myapplication.pw10.domain.PlanSummary
import com.example.myapplication.pw10.domain.Readiness
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Стан завантаження даних з REST API. */
data class GridUiState(
    val loading: Boolean = false,
    val forecasts: List<FacilityForecast> = emptyList(),
    val origin: DataOrigin? = null,
    val fetchedAt: Long? = null,
    val date: String = "",
    val retries: Int = 0,
    val rejected: Int = 0,
    val warning: String? = null,
    val error: String? = null,
)

/**
 * ViewModel ПЗ 10. Викликає Repository в корутині viewModelScope (запити скасуються разом
 * із ViewModel), а результат публікує в StateFlow — Compose перемальовує лише змінені частини.
 * Бізнес-логіка прогнозу ([OutagePlanner]) застосовується до отриманого знімка тут,
 * тому однаково працює і для свіжих даних, і для офлайн-кешу.
 */
class OutageReadinessViewModel(
    private val repository: GridRepository,
    private val server: MockGridServer? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(GridUiState())
    val state: StateFlow<GridUiState> = _state.asStateFlow()

    private val _filter = MutableStateFlow(ForecastFilter())
    val filter: StateFlow<ForecastFilter> = _filter.asStateFlow()

    val visible: StateFlow<List<FacilityForecast>> = combine(_state, _filter) { s, f -> f.apply(s.forecasts) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val summary: StateFlow<PlanSummary> = _state.map { PlanSummary.of(it.forecasts) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlanSummary())

    private val _profile = MutableStateFlow(server?.profile ?: NetworkProfile.STABLE)
    val profile: StateFlow<NetworkProfile> = _profile.asStateFlow()

    private var loadJob: Job? = null

    init {
        load(force = false)
    }

    fun load(force: Boolean = true) {
        loadJob?.cancel() // новий запит скасовує попередній
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            repository.load(force)
                .onSuccess { r ->
                    _state.value = GridUiState(
                        forecasts = OutagePlanner.plan(r.snapshot),
                        origin = r.origin,
                        fetchedAt = r.snapshot.fetchedAt,
                        date = r.snapshot.date,
                        retries = r.retries,
                        rejected = r.snapshot.rejected,
                        warning = r.warning,
                    )
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun setProfile(profile: NetworkProfile) {
        server?.profile = profile
        _profile.value = profile
    }

    fun toggleReadiness(r: Readiness) = _filter.update { it.copy(readiness = if (it.readiness == r) null else r) }

    fun setCategory(category: Int?) = _filter.update { it.copy(category = category) }

    fun setSort(sort: ForecastSort) = _filter.update { it.copy(sort = sort) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val (repository, server) = GridRepository.create(checkNotNull(this[APPLICATION_KEY]))
                OutageReadinessViewModel(repository, server)
            }
        }
    }
}
