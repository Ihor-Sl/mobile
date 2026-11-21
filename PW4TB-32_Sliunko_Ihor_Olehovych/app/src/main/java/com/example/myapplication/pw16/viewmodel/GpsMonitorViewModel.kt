package com.example.myapplication.pw16.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.facilities.Facility
import com.example.myapplication.facilities.FacilityCatalog
import com.example.myapplication.facilities.FacilityQuery
import com.example.myapplication.facilities.GeoPoint
import com.example.myapplication.facilities.PowerStatus
import com.example.myapplication.facilities.Readiness
import com.example.myapplication.facilities.SortKey
import com.example.myapplication.facilities.SortRule
import com.example.myapplication.facilities.applyQuery
import com.example.myapplication.facilities.distanceKm
import com.example.myapplication.pw16.data.LocationSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

enum class LocationPermission { UNKNOWN, GRANTED, DENIED }

/** Об'єкт разом із відстанню до користувача (null, поки позиція невідома). */
data class NearbyFacility(val facility: Facility, val distanceKm: Double?)

data class GpsUiState(
    val permission: LocationPermission = LocationPermission.UNKNOWN,
    val location: GeoPoint? = null,
    val locationError: String? = null,
    val items: List<NearbyFacility> = emptyList(),
    val selectedId: String? = null,
    val nearestReady: NearbyFacility? = null,
    val query: FacilityQuery = FacilityQuery(primary = SortRule(SortKey.DISTANCE)),
)

private data class LocationState(val point: GeoPoint? = null, val error: String? = null)

/**
 * ViewModel ПЗ 16. Потік позиції запускається лише після надання дозволу (flatMapLatest),
 * а кожне нове значення перераховує відстані, порядок списку та найближчий придатний об'єкт.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GpsMonitorViewModel(
    private val locationSource: LocationSource,
    private val all: List<Facility> = FacilityCatalog.sample(),
) : ViewModel() {
    private val permission = MutableStateFlow(LocationPermission.UNKNOWN)
    private val selectedId = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow(FacilityQuery(primary = SortRule(SortKey.DISTANCE)))

    private val location: Flow<LocationState> = permission.flatMapLatest { current ->
        if (current != LocationPermission.GRANTED) {
            flowOf(LocationState())
        } else {
            locationSource.updates()
                .map { LocationState(point = it) }
                .onStart { emit(LocationState()) }
                // Вимкнений GPS або збій сервісів не повинні завершувати роботу екрана
                .catch { emit(LocationState(error = "Не вдалося отримати геолокацію. Перевірте, чи увімкнено GPS")) }
        }
    }

    val uiState: StateFlow<GpsUiState> =
        combine(permission, location, query, selectedId) { access, position, filter, selected ->
            val origin = position.point
            val items = all.applyQuery(filter, origin).map { facility ->
                NearbyFacility(facility, origin?.let { distanceKm(it, facility.location) })
            }
            GpsUiState(
                permission = access,
                location = origin,
                locationError = position.error,
                items = items,
                selectedId = selected?.takeIf { id -> items.any { it.facility.id == id } },
                nearestReady = nearestUsable(origin),
                query = filter,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GpsUiState(items = all.map { NearbyFacility(it, null) }))

    fun onPermissionResult(granted: Boolean) {
        permission.value = if (granted) LocationPermission.GRANTED else LocationPermission.DENIED
    }

    fun select(id: String) { selectedId.value = id }

    fun toggleCard(id: String) { selectedId.update { if (it == id) null else id } }

    fun onQueryChange(value: FacilityQuery) { query.value = value }

    /** Найближчий об'єкт із працездатним резервом: аварійні та критичні не пропонуються. */
    private fun nearestUsable(origin: GeoPoint?): NearbyFacility? {
        if (origin == null) return null
        return all
            .filter { it.status != PowerStatus.FAULT && it.readiness != Readiness.CRITICAL }
            .minByOrNull { distanceKm(origin, it.location) }
            ?.let { NearbyFacility(it, distanceKm(origin, it.location)) }
    }
}
