package com.example.myapplication.pw16.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.facilities.SortKey
import com.example.myapplication.facilities.ui.FacilityCard
import com.example.myapplication.facilities.ui.FacilityFilterPanel
import com.example.myapplication.facilities.ui.FacilityMap
import com.example.myapplication.facilities.ui.MapListLayout
import com.example.myapplication.pw16.data.FusedLocationSource
import com.example.myapplication.pw16.viewmodel.GpsMonitorViewModel
import com.example.myapplication.pw16.viewmodel.GpsUiState
import com.example.myapplication.pw16.viewmodel.LocationPermission
import com.example.myapplication.ui.common.MessageBanner
import com.example.myapplication.ui.common.SectionCard
import com.example.myapplication.ui.common.Tone
import com.example.myapplication.ui.common.fmt

private val GPS_SORT_KEYS = listOf(SortKey.DISTANCE, SortKey.NAME, SortKey.POWER, SortKey.AUTONOMY, SortKey.UPDATED)
private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** Екран ПЗ 16: мапа з позицією користувача, InfoWindow та синхронізований список об'єктів. */
@Composable
fun GpsMonitorScreen() {
    val context = LocalContext.current
    val viewModel: GpsMonitorViewModel = viewModel {
        GpsMonitorViewModel(FusedLocationSource(context.applicationContext))
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun hasPermission() = LOCATION_PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    // Достатньо хоча б приблизної геолокації: користувач може не надати точну
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        viewModel.onPermissionResult(result.values.any { it })
    }

    // Під час повернення з налаштувань дозвіл міг змінитися, тому він перевіряється щоразу
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (hasPermission()) viewModel.onPermissionResult(true)
        else if (state.permission == LocationPermission.GRANTED) viewModel.onPermissionResult(false)
    }
    // Автоматичний запит лише один раз: після відмови користувач сам натискає «Надати дозвіл»
    LaunchedEffect(Unit) {
        if (!hasPermission() && viewModel.uiState.value.permission == LocationPermission.UNKNOWN) {
            launcher.launch(LOCATION_PERMISSIONS)
        }
    }

    // Натискання маркера прокручує список; відкрита панель фільтрів займає перший елемент LazyColumn
    LaunchedEffect(state.selectedId) {
        val index = state.items.indexOfFirst { it.facility.id == state.selectedId }
        if (index >= 0) listState.animateScrollToItem(index + if (filtersOpen) 1 else 0)
    }
    LaunchedEffect(filtersOpen) {
        if (filtersOpen) listState.scrollToItem(0)
    }
    // Коли найближчим стає інший об'єкт, список показує його першим (LazyColumn інакше тримається за старий key)
    val firstId = state.items.firstOrNull()?.facility?.id
    LaunchedEffect(firstId, filtersOpen) {
        if (state.selectedId == null && !filtersOpen) listState.scrollToItem(0)
    }

    Column(Modifier.fillMaxSize()) {
        LocationBanner(
            state = state,
            onRequest = { launcher.launch(LOCATION_PERMISSIONS) },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            onShowNearest = viewModel::select,
        )
        MapListLayout(
            modifier = Modifier.weight(1f),
            map = { modifier ->
                FacilityMap(
                    facilities = state.items.map { it.facility },
                    selectedId = state.selectedId,
                    onMarkerClick = viewModel::select,
                    modifier = modifier,
                    myLocation = state.location,
                    showMyLocation = state.permission == LocationPermission.GRANTED,
                )
            },
            list = { modifier ->
                Column(modifier) {
                    // Рядок керування поза списком: кнопка фільтрів доступна за будь-якої прокрутки
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Об'єктів: ${state.items.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { filtersOpen = !filtersOpen }) {
                            val active = state.query.activeFilters
                            Text((if (filtersOpen) "Сховати фільтри" else "Фільтри") + if (active > 0) " ($active)" else "")
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (filtersOpen) {
                            item(key = "filters") {
                                SectionCard { FacilityFilterPanel(state.query, viewModel::onQueryChange, GPS_SORT_KEYS) }
                            }
                        }
                        if (state.items.isEmpty()) {
                            item(key = "empty") { MessageBanner("Немає об'єктів для вибраних фільтрів", Tone.NEUTRAL) }
                        }
                        items(state.items, key = { it.facility.id }) { item ->
                            FacilityCard(
                                facility = item.facility,
                                selected = item.facility.id == state.selectedId,
                                distanceKm = item.distanceKm,
                                onClick = { viewModel.toggleCard(item.facility.id) },
                            )
                        }
                    }
                }
            },
        )
    }
}

/** Повідомляє про стан дозволу та геолокації й пропонує наступну дію. */
@Composable
private fun LocationBanner(
    state: GpsUiState,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onShowNearest: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            state.permission == LocationPermission.DENIED -> {
                MessageBanner("Без дозволу на геолокацію мапа показує лише об'єкти, відстані недоступні", Tone.WARN)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRequest) { Text("Надати дозвіл") }
                    OutlinedButton(onClick = onOpenSettings) { Text("Налаштування") }
                }
            }
            state.locationError != null -> MessageBanner(state.locationError, Tone.BAD)
            state.permission == LocationPermission.GRANTED && state.location == null ->
                MessageBanner("Визначаємо ваше місцезнаходження…", Tone.NEUTRAL)
            state.nearestReady != null -> {
                val nearest = state.nearestReady
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MessageBanner(
                        "Найближчий резерв: ${nearest.facility.name}, ${nearest.distanceKm?.fmt() ?: "—"} км",
                        Tone.GOOD,
                        Modifier.weight(1f),
                    )
                    TextButton(onClick = { onShowNearest(nearest.facility.id) }) { Text("Показати") }
                }
            }
        }
    }
}
