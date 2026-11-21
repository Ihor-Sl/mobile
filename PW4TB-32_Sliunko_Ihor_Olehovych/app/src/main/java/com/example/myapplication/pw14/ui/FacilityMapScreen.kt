package com.example.myapplication.pw14.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.myapplication.facilities.Facility
import com.example.myapplication.facilities.Readiness
import com.example.myapplication.facilities.ui.FacilityCard
import com.example.myapplication.facilities.ui.FacilityMap
import com.example.myapplication.facilities.ui.MapListLayout
import com.example.myapplication.facilities.ui.formatUpdated
import com.example.myapplication.facilities.ui.tone
import com.example.myapplication.pw14.viewmodel.FacilityMapUiState
import com.example.myapplication.pw14.viewmodel.FacilityMapViewModel
import com.example.myapplication.ui.common.InfoRow
import com.example.myapplication.ui.common.MessageBanner
import com.example.myapplication.ui.common.ScreenTitle
import com.example.myapplication.ui.common.SectionCard
import com.example.myapplication.ui.common.StatusBadge
import com.example.myapplication.ui.common.Tone
import com.example.myapplication.ui.common.fmt

private const val ROUTE_LIST = "facilities"
private const val ARG_ID = "id"
private const val ROUTE_DETAILS = "facility/{$ARG_ID}"

/** Екран ПЗ 14: список LazyColumn, пов'язаний із мапою, і перехід до деталей з параметром id. */
@Composable
fun FacilityMapScreen(viewModel: FacilityMapViewModel = viewModel { FacilityMapViewModel() }) {
    val navController = rememberNavController()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    NavHost(navController, startDestination = ROUTE_LIST, modifier = Modifier.fillMaxSize()) {
        composable(ROUTE_LIST) {
            MapWithList(
                state = state,
                onMarker = viewModel::select,
                onCard = viewModel::toggleCard,
                onReadiness = viewModel::toggleReadiness,
                onSort = viewModel::toggleSort,
                onDetails = { id -> navController.navigate("facility/$id") },
            )
        }
        composable(ROUTE_DETAILS, arguments = listOf(navArgument(ARG_ID) { type = NavType.StringType })) { entry ->
            FacilityDetails(
                facility = viewModel.facility(entry.arguments?.getString(ARG_ID)),
                onBack = { navController.popBackStack() },
            )
        }
    }
}

@Composable
private fun MapWithList(
    state: FacilityMapUiState,
    onMarker: (String) -> Unit,
    onCard: (String) -> Unit,
    onReadiness: (Readiness) -> Unit,
    onSort: () -> Unit,
    onDetails: (String) -> Unit,
) {
    val listState = rememberLazyListState()

    // Натискання маркера прокручує список до відповідної картки
    LaunchedEffect(state.selectedId, state.visible) {
        val index = state.visible.indexOfFirst { it.id == state.selectedId }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    // LazyColumn тримається за елемент із тим самим key, тому після зміни порядку список повертається на початок
    LaunchedEffect(state.sortByAutonomy, state.readiness) {
        if (state.selectedId == null) listState.scrollToItem(0)
    }

    MapListLayout(
        map = { modifier ->
            FacilityMap(state.visible, state.selectedId, onMarker, modifier)
        },
        list = { modifier ->
            Column(modifier) {
                // Фільтри в один рядок із прокруткою, щоб лишити більше місця для карток
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Readiness.entries.forEach { item ->
                        FilterChip(item in state.readiness, { onReadiness(item) }, { Text(item.label) })
                    }
                    FilterChip(state.sortByAutonomy, onSort, { Text("За автономністю") })
                }
                if (state.visible.isEmpty()) {
                    MessageBanner("Немає об'єктів для вибраних фільтрів", Tone.NEUTRAL, Modifier.padding(12.dp))
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Стабільний key зберігає стан елемента під час зміни порядку
                    items(state.visible, key = { it.id }) { facility ->
                        val selected = facility.id == state.selectedId
                        FacilityCard(
                            facility = facility,
                            selected = selected,
                            onClick = { onCard(facility.id) },
                            footer = if (selected) {
                                { TextButton(onClick = { onDetails(facility.id) }) { Text("Детальніше") } }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun FacilityDetails(facility: Facility?, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(onClick = onBack) { Text("← До списку") }
        if (facility == null) {
            MessageBanner("Об'єкт не знайдено", Tone.BAD)
            return@Column
        }
        ScreenTitle(facility.name, "${facility.type.label} · ${facility.address}")
        StatusBadge(facility.readiness.label, facility.readiness.tone())
        SectionCard(title = "Опис") { Text(facility.description) }
        SectionCard(title = "Резервне живлення") {
            InfoRow("Стан", facility.status.label)
            InfoRow("Потужність ДГУ", "${facility.reserveKw.fmt()} кВт")
            InfoRow("Навантаження", "${facility.loadKw.fmt()} кВт")
            InfoRow("Паливо", "${facility.fuelLiters.fmt()} л")
            InfoRow("Витрата", "${facility.burnLph.fmt(2)} л/год")
            InfoRow("Автономність", "${facility.autonomyHours.fmt()} год")
            InfoRow("Оновлено", formatUpdated(facility.updatedAt))
        }
    }
}
