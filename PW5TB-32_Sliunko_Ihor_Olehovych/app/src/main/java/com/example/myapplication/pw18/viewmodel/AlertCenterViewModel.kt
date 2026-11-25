package com.example.myapplication.pw18.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.pw18.domain.Alert
import com.example.myapplication.pw18.domain.AlertCategory
import com.example.myapplication.pw18.domain.AlertSource
import com.example.myapplication.pw18.domain.AlertStatus
import com.example.myapplication.pw18.notify.AlertDispatcher
import com.example.myapplication.pw18.notify.AlertScheduler
import com.example.myapplication.pw18.notify.ScheduledAlert
import com.example.myapplication.sensors.AlertLevel
import com.example.myapplication.sensors.ChannelStats
import com.example.myapplication.sensors.SensorChannel
import com.example.myapplication.sensors.SensorRules
import com.example.myapplication.sensors.SensorSource
import com.example.myapplication.ui.common.fmt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Події, які оператор може згенерувати вручну (обладнання, якого немає в телефоні). */
enum class EmergencyScenario(val label: String, val category: AlertCategory, val title: String, val text: String) {
    GRID_LOST(
        "Відключення мережі", AlertCategory.ALARM, "Відключення мережі",
        "Напруга на вводі 0 В. ДГУ запущено, навантаження переведено на резерв",
    ),
    ATS_FAULT(
        "Збій АВР", AlertCategory.ALARM, "Збій автоматичного вводу резерву",
        "АВР не перемкнув навантаження за 10 с. Потрібне ручне перемикання",
    ),
    LOW_FUEL(
        "Мало палива", AlertCategory.WARNING, "Низький рівень палива ДГУ",
        "Залишок 18 % (≈ 3,5 год роботи). Заплануйте доставку пального",
    ),
}

enum class ReminderDelay(val label: String, val ms: Long) {
    SEC_10("10 с", 10_000),
    MIN_1("1 хв", 60_000),
    MIN_15("15 хв", 15 * 60_000),
}

data class LiveReading(val channel: SensorChannel, val value: Double?, val level: AlertLevel)

/** Категоризація списку: порожня множина — усі категорії. */
data class AlertListFilter(val categories: Set<AlertCategory> = emptySet(), val activeOnly: Boolean = false)

data class AlertCenterUiState(
    val alerts: List<Alert> = emptyList(),
    val total: Int = 0,
    val activeCount: Int = 0,
    val enabled: Set<AlertCategory> = AlertCategory.entries.toSet(),
    val monitoring: Boolean = false,
    val readings: List<LiveReading> = emptyList(),
    val scheduled: List<ScheduledAlert> = emptyList(),
    val selected: Alert? = null,
    val filter: AlertListFilter = AlertListFilter(),
    val reminderDelay: ReminderDelay = ReminderDelay.SEC_10,
)

private data class Extras(
    val readings: Map<SensorChannel, Double>,
    val scheduled: List<ScheduledAlert>,
    val filter: AlertListFilter,
    val delay: ReminderDelay,
)

/**
 * ViewModel ПЗ 18. Стан тривог береться з AlertRepository, який змінюють і кнопки екрана,
 * і кнопки сповіщень, тому UI та шторка завжди синхронні. Під час моніторингу сенсори
 * порівнюються з порогами: перехід у тривогу створює сповіщення, а текст активних тривог
 * оновлюється актуальними значеннями без повторного звуку.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class AlertCenterViewModel(
    private val dispatcher: AlertDispatcher,
    private val scheduler: AlertScheduler,
    private val source: SensorSource,
    livePeriodMs: Long = 1_000,
    notifyPeriodMs: Long = 3_000,
) : ViewModel() {
    private val repository = dispatcher.repository
    private val filter = MutableStateFlow(AlertListFilter())
    private val reminderDelay = MutableStateFlow(ReminderDelay.SEC_10)
    private val lastLevels = mutableMapOf<SensorChannel, AlertLevel>()

    /** Сенсори працюють лише при увімкненому моніторингу (flatMapLatest скасовує підписку). */
    private val readings: StateFlow<Map<SensorChannel, Double>> = repository.monitoring
        .flatMapLatest { on ->
            if (!on) {
                flowOf(emptyMap())
            } else {
                MONITORED.map(source::samples).merge()
                    .scan(emptyMap<SensorChannel, ChannelStats>()) { acc, s ->
                        acc + (s.channel to (acc[s.channel] ?: ChannelStats()).accept(s.channel, s.value, s.timeMs))
                    }
                    .map { stats -> stats.mapNotNull { (channel, value) -> value.last?.let { channel to it } }.toMap() }
                    .sample(livePeriodMs)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        viewModelScope.launch { readings.collect(::evaluate) }
        // Сповіщення оновлюються рідше, ніж екран (система обмежує частоту оновлень),
        // і лише під час моніторингу. Журнал теж є джерелом: тривога від Worker чи Receiver
        // має змінити лічильник у шторці, навіть якщо показники сенсорів сталі
        viewModelScope.launch {
            repository.monitoring
                .flatMapLatest { on ->
                    if (on) combine(readings, repository.book) { values, _ -> values }.sample(notifyPeriodMs) else emptyFlow()
                }
                .collect(::pushLive)
        }
    }

    val uiState: StateFlow<AlertCenterUiState> = combine(
        repository.book,
        repository.enabled,
        repository.monitoring,
        repository.focus,
        combine(readings, scheduler.pending(), filter, reminderDelay) { r, s, f, d -> Extras(r, s, f, d) },
    ) { book, enabled, monitoring, focus, extras ->
        val shown = book.alerts.filter { alert ->
            (extras.filter.categories.isEmpty() || alert.category in extras.filter.categories) &&
                (!extras.filter.activeOnly || alert.status == AlertStatus.ACTIVE || alert.status == AlertStatus.SNOOZED)
        }
        AlertCenterUiState(
            alerts = shown,
            total = book.alerts.size,
            activeCount = book.active.size,
            enabled = enabled,
            monitoring = monitoring,
            readings = if (!monitoring) emptyList() else MONITORED.map { channel ->
                val value = extras.readings[channel]
                LiveReading(channel, value, SensorRules.level(channel, value, channel.defaultThreshold))
            },
            scheduled = extras.scheduled,
            selected = focus?.let(book::find),
            filter = extras.filter,
            reminderDelay = extras.delay,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertCenterUiState())

    fun setMonitoring(on: Boolean) {
        lastLevels.clear()
        if (on) repository.setMonitoring(true) else dispatcher.stopMonitor()
    }

    fun trigger(scenario: EmergencyScenario) {
        dispatcher.raise(scenario.category, scenario.title, scenario.text, AlertSource.OPERATOR)
    }

    /** Кілька подій одночасно: окремі сповіщення та підсумок групи в шторці. */
    fun triggerSeries() = EmergencyScenario.entries.forEach(::trigger)

    fun setReminderDelay(value: ReminderDelay) { reminderDelay.value = value }

    fun scheduleReminder() {
        val delay = reminderDelay.value
        dispatcher.scheduleReminder(
            "Тестовий запуск ДГУ",
            "Плановий запуск ДГУ під навантаженням на 15 хв (заплановано за ${delay.label}). Перевірте паливо та тиск масла",
            delay.ms,
        )
    }

    fun cancelScheduled() = scheduler.cancelAll()

    fun acknowledge(id: Int) = dispatcher.acknowledge(id)

    fun snooze(alert: Alert) = dispatcher.snooze(alert.toPayload())

    fun setCategoryEnabled(category: AlertCategory, on: Boolean) = dispatcher.setCategoryEnabled(category, on)

    fun onFilterChange(value: AlertListFilter) { filter.value = value }

    fun select(id: Int?) = repository.focus(id)

    fun clearClosed() = repository.clearClosed()

    /** Реакція лише на зміну рівня (фронт сигналу), а не на кожне вимірювання. */
    private fun evaluate(values: Map<SensorChannel, Double>) {
        values.forEach { (channel, value) ->
            val level = SensorRules.level(channel, value, channel.defaultThreshold)
            val previous = lastLevels.put(channel, level) ?: AlertLevel.NORMAL
            if (level == previous) return@forEach
            val category = when {
                level == AlertLevel.ALARM -> AlertCategory.ALARM
                level == AlertLevel.WARNING && previous == AlertLevel.NORMAL -> AlertCategory.WARNING
                else -> return@forEach
            }
            val open = repository.book.value.alerts.any {
                it.channel == channel && it.category == category &&
                    (it.status == AlertStatus.ACTIVE || it.status == AlertStatus.SNOOZED)
            }
            if (!open) {
                val prefix = if (category == AlertCategory.ALARM) "Тривога" else "Попередження"
                dispatcher.raise(category, "$prefix: ${channel.label}", liveText(channel, value), AlertSource.SENSOR, channel)
            }
        }
    }

    private fun pushLive(values: Map<SensorChannel, Double>) {
        if (!repository.monitoring.value || values.isEmpty()) return
        dispatcher.showMonitor(MONITORED.mapNotNull { channel -> values[channel]?.let { "${channel.label}: ${it.fmt(2)} ${channel.unit}" } })
        repository.book.value.alerts
            .filter { it.status == AlertStatus.ACTIVE && it.channel != null }
            .forEach { alert -> values[alert.channel]?.let { dispatcher.updateLive(alert.id, liveText(alert.channel!!, it)) } }
    }

    companion object {
        val MONITORED = listOf(
            SensorChannel.BATTERY_TEMP,
            SensorChannel.VIBRATION,
            SensorChannel.LIGHT,
            SensorChannel.BATTERY_VOLTAGE,
        )

        fun liveText(channel: SensorChannel, value: Double): String {
            val level = SensorRules.level(channel, value, channel.defaultThreshold)
            return "Зараз: ${value.fmt(2)} ${channel.unit} (${level.label.lowercase()}), поріг ${channel.defaultThreshold.fmt(2)} ${channel.unit}"
        }
    }
}
