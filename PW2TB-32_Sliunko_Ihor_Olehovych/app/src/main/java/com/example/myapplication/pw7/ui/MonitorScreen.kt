package com.example.myapplication.pw7.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.pw7.domain.Alarm
import com.example.myapplication.pw7.domain.AlarmEvaluator
import com.example.myapplication.pw7.domain.AlarmLevel
import com.example.myapplication.pw7.domain.Telemetry
import com.example.myapplication.pw7.domain.TransferStep
import com.example.myapplication.pw7.domain.TransferTestState
import com.example.myapplication.pw7.ui.components.AlarmList
import com.example.myapplication.pw7.ui.components.MetricCard
import com.example.myapplication.pw7.ui.components.OverallStatusCard
import com.example.myapplication.pw7.ui.components.StateContainer
import com.example.myapplication.pw7.ui.components.StepRow
import com.example.myapplication.pw7.ui.components.StepStatus
import com.example.myapplication.pw7.viewmodel.MonitorViewModel
import com.example.myapplication.pw7.viewmodel.TelemetryUiState
import com.example.myapplication.pw7.viewmodel.dataOrNull

private val WIDE_SCREEN = 600.dp

/**
 * ПЗ 7, просунутий рівень: View в архітектурі MVVM.
 *
 * Екран не містить бізнес-логіки та не зберігає дані сам: він підписується на StateFlow
 * з [MonitorViewModel] (collectAsStateWithLifecycle — збір зупиняється, коли екран не видно)
 * і передає у ViewModel лише події користувача (оновити, автооновлення, запуск тесту АВР).
 *
 * ViewModel створюється через фабрику й прив'язана до Activity, тому стан переживає
 * поворот екрана та перемикання між практичними заняттями.
 */
@Composable
fun MonitorScreen(viewModel: MonitorViewModel = viewModel(factory = MonitorViewModel.Factory)) {
    val telemetryState by viewModel.telemetry.collectAsStateWithLifecycle()
    val alarms by viewModel.alarms.collectAsStateWithLifecycle()
    val overallLevel by viewModel.overallLevel.collectAsStateWithLifecycle()
    val autoRefresh by viewModel.autoRefresh.collectAsStateWithLifecycle()
    val testState by viewModel.transferTest.collectAsStateWithLifecycle()
    val canStartTest by viewModel.canStartTest.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 1000.dp)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Моніторинг системи",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            RefreshControls(
                refreshing = telemetryState is TelemetryUiState.Loading ||
                    (telemetryState as? TelemetryUiState.Success)?.refreshing == true,
                autoRefresh = autoRefresh,
                onRefresh = viewModel::refresh,
                onAutoRefreshChange = viewModel::setAutoRefresh,
            )

            // Адаптивний макет: на широкому екрані телеметрія та тест АВР розташовані поруч
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val telemetryPanel = @Composable { modifier: Modifier ->
                    TelemetryPanel(telemetryState, alarms, overallLevel, viewModel::refresh, modifier)
                }
                val testPanel = @Composable { modifier: Modifier ->
                    TransferTestPanel(
                        state = testState,
                        canStart = canStartTest,
                        blockedReason = blockedReason(telemetryState),
                        onStart = viewModel::startTransferTest,
                        onReset = viewModel::resetTransferTest,
                        modifier = modifier
                    )
                }
                if (maxWidth >= WIDE_SCREEN) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                        telemetryPanel(Modifier.weight(1.2f))
                        testPanel(Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        telemetryPanel(Modifier.fillMaxWidth())
                        testPanel(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** Кнопка оновлення та перемикач автооновлення. */
@Composable
private fun RefreshControls(
    refreshing: Boolean,
    autoRefresh: Boolean,
    onRefresh: () -> Unit,
    onAutoRefreshChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onRefresh, enabled = !refreshing) {
            Text(if (refreshing) "Оновлення…" else "Оновити")
        }
        Text(
            "Автооновлення (5 с)",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        )
        Switch(checked = autoRefresh, onCheckedChange = onAutoRefreshChange)
    }
}

/** Панель телеметрії: три стани (завантаження / помилка / дані) обробляє StateContainer. */
@Composable
private fun TelemetryPanel(
    state: TelemetryUiState,
    alarms: List<Alarm>,
    overallLevel: AlarmLevel,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StateContainer(
        isLoading = state is TelemetryUiState.Loading,
        errorMessage = (state as? TelemetryUiState.Error)?.message,
        onRetry = onRetry,
        modifier = modifier
    ) {
        val data = state.dataOrNull() ?: return@StateContainer
        val subtitle = when (state) {
            is TelemetryUiState.Success -> "Оновлень: ${state.updateCount}"
            else -> "Показано останні отримані дані"
        }
        OverallStatusCard(overallLevel, subtitle)
        MetricGrid(data)
        AlarmList(alarms)
    }
}

/** Сітка показників 2 × N; колір кожної картки визначає бізнес-логіка AlarmEvaluator. */
@Composable
private fun MetricGrid(t: Telemetry) {
    val metrics = listOf(
        Triple("Напруга мережі", "%.0f В".format(t.gridVoltage), AlarmEvaluator.voltageLevel(t)),
        Triple("Частота", "%.2f Гц".format(t.gridFrequencyHz), AlarmEvaluator.frequencyLevel(t)),
        Triple("Заряд АКБ", "${t.batteryPercent} %", AlarmEvaluator.batteryLevel(t)),
        Triple("Температура АКБ", "%.1f °C".format(t.batteryTempC), AlarmEvaluator.temperatureLevel(t)),
        Triple("Завантаження ДБЖ", "${t.upsLoadPercent} %", AlarmEvaluator.upsLoadLevel(t)),
        Triple("Паливо ДГУ", "${t.fuelPercent} %", AlarmEvaluator.fuelLevel(t)),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value, level) -> MetricCard(label, value, level, Modifier.weight(1f)) }
            }
        }
        Text(
            "ДГУ: " + if (t.generatorRunning) "працює" else "зупинений (у резерві)",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** Пояснення, чому тест АВР зараз недоступний. */
private fun blockedReason(state: TelemetryUiState): String? {
    val data = (state as? TelemetryUiState.Success)?.data ?: return "Потрібні актуальні дані телеметрії"
    return when {
        data.gridVoltage < AlarmEvaluator.VOLTAGE_OUTAGE -> "Тест неможливий: немає напруги мережі"
        data.fuelPercent < AlarmEvaluator.FUEL_MIN_FOR_TEST -> "Тест неможливий: палива менше ${AlarmEvaluator.FUEL_MIN_FOR_TEST} %"
        else -> null
    }
}

/** Панель випробування АВР: кроки сценарію, поточний стан і результат. */
@Composable
private fun TransferTestPanel(
    state: TransferTestState,
    canStart: Boolean,
    blockedReason: String?,
    onStart: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Випробування АВР", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Імітація зникнення мережі та автоматичного переходу на ДГУ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            TransferStep.entries.forEach { step ->
                StepRow(step.title, stepStatus(step, state), stepDetail(step, state))
            }

            when (state) {
                is TransferTestState.Passed -> Text(
                    "Тест пройдено за %.1f с (спроб пуску ДГУ: %d)".format(state.durationSeconds, state.startAttempts),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                is TransferTestState.Failed -> Text(
                    "Відмова: ${state.reason}",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
                else -> Unit
            }
            if (state is TransferTestState.Idle && !canStart && blockedReason != null) {
                Text(blockedReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = onStart, enabled = canStart && state is TransferTestState.Idle) {
                    Text(if (state is TransferTestState.Running) "Тест триває…" else "Запустити тест")
                }
                if (state is TransferTestState.Passed || state is TransferTestState.Failed) {
                    OutlinedButton(onClick = onReset) { Text("Скинути") }
                }
            }
        }
    }
}

private fun stepStatus(step: TransferStep, state: TransferTestState): StepStatus = when (state) {
    TransferTestState.Idle -> StepStatus.PENDING
    is TransferTestState.Passed -> StepStatus.DONE
    is TransferTestState.Running -> when {
        step.ordinal < state.step.ordinal -> StepStatus.DONE
        step == state.step -> StepStatus.ACTIVE
        else -> StepStatus.PENDING
    }
    is TransferTestState.Failed -> when {
        step.ordinal < state.step.ordinal -> StepStatus.DONE
        step == state.step -> StepStatus.FAILED
        else -> StepStatus.PENDING
    }
}

private fun stepDetail(step: TransferStep, state: TransferTestState): String? =
    if (step == TransferStep.GENERATOR_START && state is TransferTestState.Running && state.step == step) {
        "Спроба пуску ${state.startAttempt}"
    } else null
