package com.example.myapplication.pw19.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.pw19.data.EnergyGenerator
import com.example.myapplication.pw19.domain.EnergyBucket
import com.example.myapplication.pw19.domain.EnergyMath
import com.example.myapplication.pw19.domain.EnergyStats
import com.example.myapplication.pw19.domain.TimeInterval
import com.example.myapplication.pw19.domain.ZoneUsage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Які елементи показувати на комбінованому графіку. */
data class ChartOptions(
    val bars: Boolean = true,
    val trend: Boolean = true,
    val average: Boolean = true,
    val peak: Boolean = true,
)

data class EnergyChartsUiState(
    val interval: TimeInterval = TimeInterval.DAY,
    val buckets: List<EnergyBucket> = emptyList(),
    val trend: List<Double> = emptyList(),
    val stats: EnergyStats? = null,
    val zones: List<ZoneUsage> = emptyList(),
    val options: ChartOptions = ChartOptions(),
    val selected: Int? = null,
    val live: Boolean = false,
    val lastHourMs: Long = 0,
)

/**
 * ViewModel ПЗ 19. Історія обліку — StateFlow; будь-яка зміна даних, інтервалу чи параметрів
 * перераховує стовпчики, тренд і показники, і графіки оновлюються «реактивно».
 * Режим «Живі дані» додає нову годину обліку кожні [livePeriodMs] мс (прискорений час).
 */
class EnergyChartsViewModel(
    private val generator: EnergyGenerator = EnergyGenerator(),
    now: Long = System.currentTimeMillis(),
    private val livePeriodMs: Long = 1_500,
    zone: TimeZone = TimeZone.getDefault(),
) : ViewModel() {
    private val history = MutableStateFlow(generator.history(now, MAX_HOURS))
    private val interval = MutableStateFlow(TimeInterval.DAY)
    private val options = MutableStateFlow(ChartOptions())
    private val selected = MutableStateFlow<Int?>(null)
    private val live = MutableStateFlow(false)
    private var liveJob: Job? = null

    private val locale = Locale.forLanguageTag("uk-UA")
    private val formats = mapOf(
        TimeInterval.DAY to SimpleDateFormat("HH:mm", locale),
        TimeInterval.WEEK to SimpleDateFormat("EE HH", locale),
        TimeInterval.MONTH to SimpleDateFormat("dd.MM", locale),
    ).onEach { it.value.timeZone = zone }

    val uiState: StateFlow<EnergyChartsUiState> =
        combine(history, interval, options, selected, live) { hours, range, opts, index, isLive ->
            val format = formats.getValue(range)
            val buckets = EnergyMath.buckets(hours, range) { format.format(Date(it)) }
            val window = EnergyMath.window(hours, range)
            EnergyChartsUiState(
                interval = range,
                buckets = buckets,
                trend = EnergyMath.movingAverage(buckets.map { it.total }),
                stats = EnergyMath.stats(buckets, window),
                zones = EnergyMath.zones(window),
                options = opts,
                selected = index?.takeIf { it in buckets.indices },
                live = isLive,
                lastHourMs = hours.last().timeMs,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EnergyChartsUiState())

    /** Зміна інтервалу скидає вибраний стовпчик: індекси в новому ряду інші. */
    fun setInterval(value: TimeInterval) {
        if (interval.value == value) return
        selected.value = null
        interval.value = value
    }

    fun onOptionsChange(value: ChartOptions) {
        // Хоча б один тип графіка має лишатися видимим
        if (!value.bars && !value.trend) return
        options.value = value
    }

    fun select(index: Int?) { selected.value = index }

    fun setLive(on: Boolean) {
        live.value = on
        liveJob?.cancel()
        if (!on) return
        liveJob = viewModelScope.launch {
            while (isActive) {
                delay(livePeriodMs)
                appendHour()
            }
        }
    }

    /** Нова година обліку; найстаріша відкидається, щоб ряд мав сталу довжину. */
    fun appendHour() {
        history.update { hours -> (hours + generator.hour(hours.last().timeMs + EnergyGenerator.HOUR_MS)).takeLast(MAX_HOURS) }
    }

    /** Текст підказки для стовпчика: період, розподіл за джерелами та відхилення від середнього. */
    fun describe(state: EnergyChartsUiState, index: Int): String {
        val bucket = state.buckets.getOrNull(index) ?: return ""
        val average = state.stats?.average ?: bucket.total
        val delta = (bucket.total - average) / average * 100
        return buildString {
            append(periodLabel(state.interval, bucket)).append('\n')
            append("Мережа %.0f · ДГУ %.0f · АКБ %.0f\n".format(bucket.grid, bucket.genset, bucket.battery))
            append("Разом %.0f кВт·год (%+.0f %% до середнього)".format(bucket.total, delta))
        }
    }

    private fun periodLabel(range: TimeInterval, bucket: EnergyBucket): String {
        val end = bucket.startMs + bucket.hours * EnergyGenerator.HOUR_MS
        val format = if (range == TimeInterval.DAY) formats.getValue(TimeInterval.DAY) else fullFormat
        return "${format.format(Date(bucket.startMs))} – ${format.format(Date(end))}"
    }

    private val fullFormat = SimpleDateFormat("dd.MM HH:mm", locale).apply { timeZone = zone }

    override fun onCleared() {
        liveJob?.cancel()
    }

    companion object {
        const val MAX_HOURS = 720
    }
}
