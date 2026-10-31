package com.example.myapplication.pw10.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.pw10.data.DataOrigin
import com.example.myapplication.pw10.data.remote.NetworkProfile
import com.example.myapplication.pw10.domain.ForecastFilter
import com.example.myapplication.pw10.domain.ForecastSort
import com.example.myapplication.pw10.domain.PlanSummary
import com.example.myapplication.pw10.domain.Readiness
import com.example.myapplication.pw10.ui.components.FacilityCard
import com.example.myapplication.pw10.ui.components.tone
import com.example.myapplication.pw10.viewmodel.GridUiState
import com.example.myapplication.pw10.viewmodel.OutageReadinessViewModel
import com.example.myapplication.ui.common.MessageBanner
import com.example.myapplication.ui.common.MetricTile
import com.example.myapplication.ui.common.ScreenTitle
import com.example.myapplication.ui.common.Tone
import com.example.myapplication.ui.common.fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ПЗ 10, просунутий рівень: готовність об'єктів до графіка планових відключень.
 *
 * Дані з трьох REST-ресурсів (Retrofit + Gson) → Repository (повтори, кеш) → ViewModel → UI.
 * Адаптивність: LazyVerticalGrid з GridCells.Adaptive(330.dp) — на телефоні одна колонка карток,
 * у ландшафті та на планшеті дві й більше; заголовок займає всю ширину (GridItemSpan).
 */
@Composable
fun OutageReadinessScreen(viewModel: OutageReadinessViewModel = viewModel(factory = OutageReadinessViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(330.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Header(
                state, summary, filter, profile,
                onReload = { viewModel.load(force = true) },
                onProfile = viewModel::setProfile,
                onReadiness = viewModel::toggleReadiness,
                onCategory = viewModel::setCategory,
                onSort = viewModel::setSort,
            )
        }
        items(visible, key = { it.facility.id }) { FacilityCard(it) }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Header(
    state: GridUiState,
    summary: PlanSummary,
    filter: ForecastFilter,
    profile: NetworkProfile,
    onReload: () -> Unit,
    onProfile: (NetworkProfile) -> Unit,
    onReadiness: (Readiness) -> Unit,
    onCategory: (Int?) -> Unit,
    onSort: (ForecastSort) -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenTitle("Графік відключень", "Чи вистачить резерву об'єктам на ${state.date.ifEmpty { "сьогодні" }}")

        Text("Якість мережі (імітація сервера)", style = MaterialTheme.typography.labelLarge)
        val profiles = NetworkProfile.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            profiles.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = p == profile, onClick = { onProfile(p) },
                    shape = SegmentedButtonDefaults.itemShape(i, profiles.size), label = { Text(p.label) }
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val origin = state.origin
                if (origin != null) {
                    Text(
                        "Джерело: ${origin.label}" + (state.fetchedAt?.let { " · дані від ${timeFormat.format(Date(it))}" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Повторних спроб: ${state.retries} · відкинуто некоректних записів: ${state.rejected}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (state.loading) {
                    Text("Завантаження з REST API…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else TextButton(onClick = onReload) { Text("Оновити") }
        }
        state.warning?.let { MessageBanner("$it. Показано офлайн-кеш.", Tone.WARN) }
        state.error?.let { MessageBanner("$it. Кешованих даних немає — спробуйте пізніше.", Tone.BAD) }
        if (state.origin == DataOrigin.MEMORY) MessageBanner("Дані взято з кешу в пам'яті (менше 60 с) — запит до сервера не надсилався", Tone.NEUTRAL)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Readiness.entries.forEach { r ->
                MetricTile(
                    r.label, (summary.byReadiness[r] ?: 0).toString(), Modifier.weight(1f),
                    tone = r.tone(), selected = filter.readiness == r, onClick = { onReadiness(r) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Довезти пального", "${summary.fuelToDeliverL.fmt(0)} л", Modifier.weight(1f), tone = if (summary.fuelToDeliverL > 0) Tone.WARN else Tone.GOOD)
            MetricTile("Зараз на резерві", "${summary.onBackupNow} об'єкт(и)", Modifier.weight(1f))
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(filter.category == null, { onCategory(null) }, label = { Text("Усі") })
            FilterChip(filter.category == 1, { onCategory(1) }, label = { Text("I категорія") })
            FilterChip(filter.category == 2, { onCategory(2) }, label = { Text("II категорія") })
            Box {
                TextButton(onClick = { sortMenu = true }) { Text("↕ ${filter.sort.label}") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    ForecastSort.entries.forEach { s ->
                        DropdownMenuItem(text = { Text(s.label) }, onClick = { onSort(s); sortMenu = false })
                    }
                }
            }
        }
    }
}
