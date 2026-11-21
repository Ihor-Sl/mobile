package com.example.myapplication.pw13.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.pw13.data.PowerScenario
import com.example.myapplication.pw13.data.SimulatedPowerSensors
import com.example.myapplication.pw13.domain.LinkState
import com.example.myapplication.pw13.domain.PowerAssessor
import com.example.myapplication.pw13.domain.PowerReading
import com.example.myapplication.pw13.domain.SensorFaultException
import com.example.myapplication.pw13.domain.SensorId
import com.example.myapplication.pw13.domain.SensorSample
import com.example.myapplication.pw13.domain.SupplyMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Точка графіка: фактичне та прогнозоване навантаження. */
data class LoadPoint(val actual: Double, val forecast: Double)

data class PowerUiState(
    val reading: PowerReading? = null,
    val forecastKw: Double? = null,
    val thresholdHours: Double = DEFAULT_THRESHOLD,
    val belowThreshold: Boolean = false,
    val links: Map<SensorId, LinkState> = SensorId.entries.associateWith { LinkState.Online },
    val history: List<LoadPoint> = emptyList(),
    val events: List<String> = emptyList(),
)

const val DEFAULT_THRESHOLD = 12.0

/**
 * ViewModel ПЗ 13. Кожен «холодний» потік датчика проходить конвеєр:
 * filter (відкидання збоїв) -> retryWhen (повтор із паузою 1-2-4 с) -> catch (остаточна помилка) ->
 * shareIn («гарячий» потік для кількох підписників). Далі потоки об'єднує combine.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class PowerFlowViewModel(
    private val sensors: SimulatedPowerSensors = SimulatedPowerSensors(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val retryBaseMs: Long = 1_000,
) : ViewModel() {

    private val links = MutableStateFlow(SensorId.entries.associateWith<SensorId, LinkState> { LinkState.Online })
    private val events = MutableStateFlow<List<String>>(emptyList())
    private val restarts = SensorId.entries.associateWith { MutableStateFlow(0) }
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _thresholdText = MutableStateFlow("12")
    val thresholdText: StateFlow<String> = _thresholdText.asStateFlow()
    val scenario: StateFlow<PowerScenario> = sensors.scenario

    private val grid = resilient(SensorId.GRID)
    private val fuel = resilient(SensorId.FUEL)
    private val battery = resilient(SensorId.BATTERY)
    private val load = resilient(SensorId.LOAD)

    /** Прогноз: згладжене значення накопичується оператором runningFold; одне вимірювання — одна точка. */
    private val loadPoints: Flow<LoadPoint> = load
        .runningFold<SensorSample, LoadPoint?>(null) { previous, sample ->
            LoadPoint(sample.value, PowerAssessor.smooth(previous?.forecast, sample.value))
        }
        .filterNotNull()

    private val forecast: Flow<Double> = loadPoints.map { it.forecast }

    /** debounce: поріг застосовується лише після паузи у введенні. */
    private val threshold: Flow<Double> = _thresholdText
        .debounce(INPUT_DEBOUNCE_MS)
        .mapNotNull(PowerAssessor::parseThreshold)
        .distinctUntilChanged()

    private val readings: Flow<PowerReading> = combine(grid, fuel, battery, load) { g, f, b, l ->
        PowerAssessor.assess(g.value, f.value, b.value, l.value)
    }.onEach(::logModeChange)

    private val history: Flow<List<LoadPoint>> = loadPoints
        .runningFold(emptyList<LoadPoint>()) { list, point -> (list + point).takeLast(HISTORY_SIZE) }

    private var lastMode: SupplyMode? = null

    val uiState: StateFlow<PowerUiState> = combine(
        combine(readings, forecast, threshold) { r, f, t -> Triple(r, f, t) },
        links,
        history,
        events,
    ) { (reading, predicted, limit), linkStates, points, log ->
        PowerUiState(
            reading = reading,
            forecastKw = predicted,
            thresholdHours = limit,
            belowThreshold = reading.autonomyHours < limit,
            links = linkStates,
            history = points,
            events = log,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PowerUiState())

    fun onThresholdChange(text: String) { _thresholdText.value = text }

    fun setScenario(value: PowerScenario) {
        sensors.setScenario(value)
        log("Сценарій: ${value.label}")
    }

    /** Короткий збій: відновлюється автоматично через retryWhen. */
    fun injectTransientFault(id: SensorId) {
        sensors.injectFault(id, 1)
        log("Імітовано короткий збій: ${id.label}")
    }

    /** Тривалий збій: вичерпує всі повтори, після чого потрібне ручне перепідключення. */
    fun injectPermanentFault(id: SensorId) {
        sensors.injectFault(id, PERMANENT_FAULTS)
        log("Імітовано тривалий збій: ${id.label}")
    }

    /** Нове значення лічильника змушує flatMapLatest створити потік датчика заново. */
    fun reconnect(id: SensorId) {
        sensors.clearFault(id)
        setLink(id, LinkState.Online)
        restarts.getValue(id).update { it + 1 }
        log("Перепідключення: ${id.label}")
    }

    private fun resilient(id: SensorId): SharedFlow<SensorSample> =
        restarts.getValue(id)
            .flatMapLatest {
                sensors.stream(id)
                    .filter(PowerAssessor::isValid)
                    .onEach { setLink(id, LinkState.Online) }
                    .retryWhen { cause, attempt ->
                        if (cause !is SensorFaultException || attempt >= MAX_RETRIES) return@retryWhen false
                        setLink(id, LinkState.Retrying(attempt.toInt() + 1, MAX_RETRIES))
                        log("${id.label}: повтор ${attempt + 1} з $MAX_RETRIES")
                        delay(retryBaseMs shl attempt.toInt())
                        true
                    }
                    .catch { error ->
                        setLink(id, LinkState.Failed(error.message ?: "Помилка датчика"))
                        log("${id.label}: зв'язок втрачено")
                    }
            }
            .flowOn(dispatcher)
            // «Гарячий» потік: один генератор на всіх підписників, останнє значення зберігається
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private fun setLink(id: SensorId, state: LinkState) {
        links.update { current -> if (current[id] == state) current else current + (id to state) }
    }

    private fun logModeChange(reading: PowerReading) {
        if (reading.mode != lastMode) {
            if (lastMode != null) log("Джерело живлення: ${reading.mode.label}")
            lastMode = reading.mode
        }
    }

    private fun log(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        events.update { (listOf(line) + it).take(MAX_EVENTS) }
    }

    private companion object {
        const val MAX_RETRIES = 3
        const val PERMANENT_FAULTS = 100
        const val INPUT_DEBOUNCE_MS = 300L
        const val HISTORY_SIZE = 30
        const val MAX_EVENTS = 6
    }
}
