package com.example.myapplication.pw17.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.sensors.AlertLevel
import com.example.myapplication.sensors.ChannelStats
import com.example.myapplication.sensors.SensorChannel
import com.example.myapplication.sensors.SensorGroup
import com.example.myapplication.sensors.SensorRules
import com.example.myapplication.sensors.SensorSource
import com.example.myapplication.ui.common.fmt
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Рядок таблиці: канал, накопичена статистика та стан відносно порогу. */
data class ChannelRow(
    val channel: SensorChannel,
    val available: Boolean,
    val stats: ChannelStats,
    val threshold: Double,
) {
    val value: Double? get() = stats.last
    val level: AlertLevel = SensorRules.level(channel, stats.last, threshold)
    val loadPercent: Double? = stats.last?.let { SensorRules.loadPercent(channel, it, threshold) }
}

enum class PanelSort(val label: String) {
    LOAD("% порогу"),
    NAME("Назва"),
    GROUP("Тип"),
}

/**
 * Умови відображення таблиці: типи сенсорів, діапазон значень у відсотках порогу та сортування.
 * Верхня межа діапазону [MAX_RANGE] означає «і більше».
 */
data class PanelQuery(
    val groups: Set<SensorGroup> = emptySet(),
    val loadRange: ClosedFloatingPointRange<Float> = 0f..MAX_RANGE,
    val sort: PanelSort = PanelSort.LOAD,
    val descending: Boolean = true,
) {
    val rangeActive: Boolean get() = loadRange.start > 0f || loadRange.endInclusive < MAX_RANGE

    companion object {
        const val MAX_RANGE = 150f
    }
}

private val collator: Collator = Collator.getInstance(Locale.forLanguageTag("uk-UA"))

/** Фільтрація за типом і діапазоном та сортування; рядки без даних завжди в кінці списку. */
fun List<ChannelRow>.applyQuery(query: PanelQuery): List<ChannelRow> {
    val filtered = filter { row ->
        val groupOk = query.groups.isEmpty() || row.channel.group in query.groups
        val rangeOk = !query.rangeActive || row.loadPercent?.let { load ->
            load >= query.loadRange.start && (query.loadRange.endInclusive >= PanelQuery.MAX_RANGE || load <= query.loadRange.endInclusive)
        } == true
        groupOk && rangeOk
    }
    val comparator: Comparator<ChannelRow> = when (query.sort) {
        PanelSort.LOAD -> compareBy { it.loadPercent ?: 0.0 }
        PanelSort.NAME -> Comparator { a, b -> collator.compare(a.channel.label, b.channel.label) }
        PanelSort.GROUP -> compareBy<ChannelRow> { it.channel.group.ordinal }.thenBy { it.channel.ordinal }
    }
    val (withData, noData) = filtered.partition { it.value != null }
    return withData.sortedWith(if (query.descending) comparator.reversed() else comparator) + noData
}

data class SensorPanelUiState(
    val rows: List<ChannelRow> = emptyList(),
    val visible: Set<SensorChannel> = emptySet(),
    val available: Set<SensorChannel> = emptySet(),
    val query: PanelQuery = PanelQuery(),
    val expanded: SensorChannel? = null,
    val alarms: List<ChannelRow> = emptyList(),
    val hiddenByFilter: Int = 0,
    val events: List<String> = emptyList(),
)

/**
 * ViewModel ПЗ 17. Потоки обраних сенсорів об'єднуються (merge), кожне вимірювання
 * перевіряється та накопичується оператором scan, а sample обмежує частоту оновлення UI.
 * Результат публікується як StateFlow, тому інтерфейс оновлюється «реактивно».
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SensorPanelViewModel(
    private val source: SensorSource,
    uiPeriodMs: Long = UI_PERIOD_MS,
) : ViewModel() {
    private val available = SensorChannel.entries.filter(source::isAvailable).toSet()
    private val visible = MutableStateFlow(DEFAULT_VISIBLE intersect available)
    private val thresholds = MutableStateFlow(SensorChannel.entries.associateWith { it.defaultThreshold })
    private val query = MutableStateFlow(PanelQuery())
    private val expanded = MutableStateFlow<SensorChannel?>(null)
    private val events = MutableStateFlow<List<String>>(emptyList())
    private val lastLevels = mutableMapOf<SensorChannel, AlertLevel>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** flatMapLatest перепідписується при зміні набору: приховані сенсори відключаються від SensorManager. */
    private val stats: Flow<Map<SensorChannel, ChannelStats>> = visible
        .flatMapLatest { channels -> channels.map(source::samples).merge() }
        .scan(emptyMap<SensorChannel, ChannelStats>()) { acc, sample ->
            acc + (sample.channel to (acc[sample.channel] ?: ChannelStats()).accept(sample.channel, sample.value, sample.timeMs))
        }
        .sample(uiPeriodMs)

    private val rows: Flow<List<ChannelRow>> = combine(stats, thresholds, ::rowsOf).onEach(::logTransitions)

    val uiState: StateFlow<SensorPanelUiState> =
        combine(rows, visible, query, expanded, events, ::buildState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), buildState(rowsOf(emptyMap(), thresholds.value), visible.value))

    private fun rowsOf(collected: Map<SensorChannel, ChannelStats>, limits: Map<SensorChannel, Double>) =
        SensorChannel.entries.map { ChannelRow(it, it in available, collected[it] ?: ChannelStats(), limits.getValue(it)) }

    private fun buildState(
        all: List<ChannelRow>,
        shownSet: Set<SensorChannel>,
        filter: PanelQuery = PanelQuery(),
        open: SensorChannel? = null,
        log: List<String> = emptyList(),
    ): SensorPanelUiState {
        val shown = all.filter { it.channel in shownSet }
        val result = shown.applyQuery(filter)
        return SensorPanelUiState(
            rows = result,
            visible = shownSet,
            available = available,
            query = filter,
            expanded = open,
            alarms = shown.filter { it.level == AlertLevel.ALARM },
            hiddenByFilter = shown.size - result.size,
            events = log,
        )
    }

    fun toggleVisible(channel: SensorChannel) {
        if (channel !in available) return
        visible.update { if (channel in it) it - channel else it + channel }
    }

    fun setThreshold(channel: SensorChannel, value: Double) {
        thresholds.update { it + (channel to SensorRules.roundThreshold(channel, value)) }
    }

    fun onQueryChange(value: PanelQuery) { query.value = value }

    fun toggleExpanded(channel: SensorChannel) { expanded.update { if (it == channel) null else channel } }

    /** Журнал фіксує лише переходи між станами, а не кожне вимірювання. */
    private fun logTransitions(rows: List<ChannelRow>) {
        rows.filter { it.value != null }.forEach { row ->
            // Перше вимірювання порівнюється з нормою: тривога на старті теж потрапляє в журнал
            val previous = lastLevels.put(row.channel, row.level) ?: AlertLevel.NORMAL
            if (previous == row.level) return@forEach
            val value = "${row.value!!.fmt()} ${row.channel.unit}"
            when {
                row.level == AlertLevel.ALARM ->
                    log("Тривога: ${row.channel.label} $value (поріг ${row.threshold.fmt()})")
                previous == AlertLevel.ALARM ->
                    log("${row.channel.label}: повернення з тривоги, $value")
            }
        }
    }

    private fun log(message: String) {
        events.update { (listOf("${timeFormat.format(Date())}  $message") + it).take(MAX_EVENTS) }
    }

    companion object {
        const val UI_PERIOD_MS = 250L
        const val MAX_EVENTS = 6

        val DEFAULT_VISIBLE = setOf(
            SensorChannel.VIBRATION,
            SensorChannel.ROTATION,
            SensorChannel.MAGNETIC,
            SensorChannel.LIGHT,
            SensorChannel.BATTERY_TEMP,
            SensorChannel.BATTERY_VOLTAGE,
        )
    }
}
