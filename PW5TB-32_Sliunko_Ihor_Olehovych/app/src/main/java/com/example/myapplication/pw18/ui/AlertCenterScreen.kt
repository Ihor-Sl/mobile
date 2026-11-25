package com.example.myapplication.pw18.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.pw18.data.AlertRepository
import com.example.myapplication.pw18.domain.Alert
import com.example.myapplication.pw18.domain.AlertCategory
import com.example.myapplication.pw18.domain.AlertStatus
import com.example.myapplication.pw18.notify.AlertDispatcher
import com.example.myapplication.pw18.notify.AlertNotifier
import com.example.myapplication.pw18.notify.WorkAlertScheduler
import com.example.myapplication.pw18.viewmodel.AlertCenterUiState
import com.example.myapplication.pw18.viewmodel.AlertCenterViewModel
import com.example.myapplication.pw18.viewmodel.AlertListFilter
import com.example.myapplication.pw18.viewmodel.EmergencyScenario
import com.example.myapplication.pw18.viewmodel.ReminderDelay
import com.example.myapplication.sensors.AlertLevel
import com.example.myapplication.sensors.AndroidSensorSource
import com.example.myapplication.ui.common.InfoRow
import com.example.myapplication.ui.common.MessageBanner
import com.example.myapplication.ui.common.MetricTile
import com.example.myapplication.ui.common.ScreenTitle
import com.example.myapplication.ui.common.SectionCard
import com.example.myapplication.ui.common.StatusBadge
import com.example.myapplication.ui.common.Tone
import com.example.myapplication.ui.common.fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** Екран ПЗ 18: локальні сповіщення з діями, синхронізовані з журналом тривог. */
@Composable
fun AlertCenterScreen() {
    val app = LocalContext.current.applicationContext
    val notifier = remember { AlertNotifier(app) }
    val viewModel: AlertCenterViewModel = viewModel {
        val scheduler = WorkAlertScheduler(app)
        AlertCenterViewModel(AlertDispatcher(AlertRepository.shared, notifier, scheduler), scheduler, AndroidSensorSource(app))
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Дозвіл POST_NOTIFICATIONS (Android 13+): запит один раз, далі — перехід у налаштування
    var canPost by remember { mutableStateOf(notifier.canPost()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canPost = notifier.canPost() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canPost = notifier.canPost() }
    LaunchedEffect(Unit) {
        if (!canPost && !asked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val requestPermission = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !asked) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openAppNotificationSettings(app)
        }
    }

    // Поточний час для зворотного відліку запланованих сповіщень
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val journal: @Composable () -> Unit = {
            JournalPanel(state, canPost, requestPermission, viewModel)
        }
        val controls: @Composable () -> Unit = { ControlPanel(state, now, viewModel, onChannelSettings = { openChannelSettings(app, it) }) }
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScrollColumn(Modifier.weight(1f)) { journal() }
                ScrollColumn(Modifier.weight(1f)) { controls() }
            }
        } else {
            ScrollColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                journal()
                controls()
            }
        }
    }
}

@Composable
private fun ScrollColumn(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private fun Long.clock(): String = timeFormat.format(Date(this))

private fun AlertCategory.tone() = when (this) {
    AlertCategory.ALARM -> Tone.BAD
    AlertCategory.WARNING -> Tone.WARN
    AlertCategory.REMINDER -> Tone.NEUTRAL
}

private fun Alert.statusTone() = when (status) {
    AlertStatus.ACTIVE -> category.tone()
    AlertStatus.SNOOZED -> Tone.WARN
    AlertStatus.ACKNOWLEDGED -> Tone.GOOD
    AlertStatus.DISMISSED -> Tone.NEUTRAL
}

private fun AlertLevel.tone() = when (this) {
    AlertLevel.NORMAL -> Tone.GOOD
    AlertLevel.WARNING -> Tone.WARN
    AlertLevel.ALARM -> Tone.BAD
    AlertLevel.UNKNOWN -> Tone.NEUTRAL
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JournalPanel(
    state: AlertCenterUiState,
    canPost: Boolean,
    onRequestPermission: () -> Unit,
    viewModel: AlertCenterViewModel,
) {
    ScreenTitle("Центр сповіщень", "Тривоги об'єкта аварійного живлення з діями у шторці")
    if (!canPost) {
        MessageBanner("Сповіщення вимкнено: тривоги фіксуються лише в журналі застосунку", Tone.BAD)
        OutlinedButton(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) { Text("Дозволити сповіщення") }
    }
    state.selected?.let { alert -> AlertDetails(alert, viewModel) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricTile("Активні", "${state.activeCount}", Modifier.weight(1f), if (state.activeCount == 0) Tone.GOOD else Tone.BAD)
        MetricTile("У журналі", "${state.total}", Modifier.weight(1f))
        MetricTile("Заплановано", "${state.scheduled.size}", Modifier.weight(1f))
    }
    SectionCard(title = "Моніторинг сенсорів") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Стежити за порогами й показувати дані у шторці", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = state.monitoring, onCheckedChange = viewModel::setMonitoring)
        }
        if (state.monitoring) {
            state.readings.forEach { reading ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(reading.channel.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Поріг ${reading.channel.defaultThreshold.fmt(2)} ${reading.channel.unit}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(reading.value?.let { "${it.fmt(2)} ${reading.channel.unit}" } ?: "—", Modifier.padding(end = 8.dp))
                    StatusBadge(reading.level.label, reading.level.tone())
                }
            }
        }
    }
    SectionCard(title = "Журнал тривог") {
        val filter = state.filter
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AlertCategory.entries.forEach { category ->
                FilterChip(
                    selected = category in filter.categories,
                    onClick = {
                        val next = if (category in filter.categories) filter.categories - category else filter.categories + category
                        viewModel.onFilterChange(filter.copy(categories = next))
                    },
                    label = { Text(category.label) },
                )
            }
            FilterChip(
                selected = filter.activeOnly,
                onClick = { viewModel.onFilterChange(filter.copy(activeOnly = !filter.activeOnly)) },
                label = { Text("Лише відкриті") },
            )
        }
        if (state.alerts.isEmpty()) Text("Тривог немає", style = MaterialTheme.typography.bodySmall)
        state.alerts.forEach { alert -> AlertItem(alert, viewModel) }
        if (state.total > state.activeCount) {
            TextButton(onClick = viewModel::clearClosed) { Text("Очистити закриті") }
        }
    }
}

@Composable
private fun AlertItem(alert: Alert, viewModel: AlertCenterViewModel) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().clickable { viewModel.select(alert.id) },
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(alert.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                StatusBadge(alert.status.label, alert.statusTone())
            }
            Text(alert.text, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(alert.category.label, alert.category.tone())
                val extra = alert.snoozedUntil?.takeIf { alert.status == AlertStatus.SNOOZED }?.let { " · до ${it.clock()}" } ?: ""
                val muted = if (alert.posted || alert.status != AlertStatus.ACTIVE) "" else " · без сповіщення"
                Text(
                    "${alert.source.label} · ${alert.createdAt.clock()}$extra$muted",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (alert.status == AlertStatus.ACTIVE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.acknowledge(alert.id) }) { Text("Підтвердити") }
                    OutlinedButton(onClick = { viewModel.snooze(alert) }) { Text("Відкласти") }
                }
            }
        }
    }
}

/** Картка деталей: відкривається з кнопки «Деталі» у сповіщенні або натисканням у журналі. */
@Composable
private fun AlertDetails(alert: Alert, viewModel: AlertCenterViewModel) {
    SectionCard(title = "Деталі тривоги #${alert.id}") {
        Text(alert.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(alert.text, style = MaterialTheme.typography.bodyMedium)
        InfoRow("Категорія (канал)", alert.category.label)
        InfoRow("Стан", alert.status.label)
        InfoRow("Джерело", alert.source.label + (alert.channel?.let { " · ${it.source}" } ?: ""))
        InfoRow("Створено", alert.createdAt.clock())
        InfoRow("Остання зміна", alert.updatedAt.clock())
        InfoRow("Відкладань", "${alert.snoozeCount}")
        InfoRow("Показано в шторці", if (alert.posted) "так" else "ні")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (alert.status == AlertStatus.ACTIVE) {
                Button(onClick = { viewModel.acknowledge(alert.id) }) { Text("Підтвердити") }
            }
            TextButton(onClick = { viewModel.select(null) }) { Text("Закрити") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlPanel(
    state: AlertCenterUiState,
    now: Long,
    viewModel: AlertCenterViewModel,
    onChannelSettings: (String) -> Unit,
) {
    SectionCard(title = "Події об'єкта (миттєві сповіщення)") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EmergencyScenario.entries.forEach { scenario ->
                OutlinedButton(onClick = { viewModel.trigger(scenario) }) { Text(scenario.label) }
            }
        }
        Button(onClick = viewModel::triggerSeries, modifier = Modifier.fillMaxWidth()) { Text("Серія з 3 подій одночасно") }
    }
    SectionCard(title = "Нагадування за розкладом (WorkManager)") {
        Text("Затримка", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReminderDelay.entries.forEach { delay ->
                FilterChip(selected = delay == state.reminderDelay, onClick = { viewModel.setReminderDelay(delay) }, label = { Text(delay.label) })
            }
        }
        Button(onClick = viewModel::scheduleReminder, modifier = Modifier.fillMaxWidth()) { Text("Запланувати тестовий запуск ДГУ") }
        state.scheduled.forEach { item ->
            val left = ((item.dueAt - now) / 1000).coerceAtLeast(0)
            InfoRow(
                (if (item.snooze) "Повтор: " else "") + item.title,
                "через %d:%02d".format(left / 60, left % 60),
            )
        }
        if (state.scheduled.isNotEmpty()) {
            TextButton(onClick = viewModel::cancelScheduled) { Text("Скасувати всі") }
        }
    }
    SectionCard(title = "Категорії та канали") {
        AlertCategory.entries.forEachIndexed { index, category ->
            if (index > 0) HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(category.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(category.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = category in state.enabled, onCheckedChange = { viewModel.setCategoryEnabled(category, it) })
            }
            TextButton(onClick = { onChannelSettings(category.channelId) }) { Text("Звук і пріоритет каналу") }
        }
    }
}

private fun openChannelSettings(context: Context, channelId: String) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun openAppNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
