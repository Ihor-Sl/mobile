package com.example.myapplication.pw7.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myapplication.pw7.data.FakeTelemetryRepository
import com.example.myapplication.pw7.data.TelemetryRepository
import com.example.myapplication.pw7.domain.Alarm
import com.example.myapplication.pw7.domain.AlarmEvaluator
import com.example.myapplication.pw7.domain.AlarmLevel
import com.example.myapplication.pw7.domain.Telemetry
import com.example.myapplication.pw7.domain.TransferStep
import com.example.myapplication.pw7.domain.TransferTestRules
import com.example.myapplication.pw7.domain.TransferTestState
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Стан завантаження телеметрії: завантаження → успіх або помилка. */
sealed interface TelemetryUiState {
    data object Loading : TelemetryUiState

    /** [refreshing] — дані показуються, але вже йде повторний запит. */
    data class Success(val data: Telemetry, val updateCount: Int, val refreshing: Boolean = false) : TelemetryUiState

    /** [lastData] — останні успішно отримані дані, щоб не «губити» екран при збої зв'язку. */
    data class Error(val message: String, val lastData: Telemetry?) : TelemetryUiState
}

/**
 * ViewModel (MVVM) екрана моніторингу.
 *
 * - Отримує дані з Model ([TelemetryRepository]) у корутинах viewModelScope — вони автоматично
 *   скасовуються, коли ViewModel знищується.
 * - Віддає View лише незмінні StateFlow; змінювати стан може тільки ViewModel (MutableStateFlow приватні).
 * - Переживає зміну конфігурації (поворот екрана): дані та запущений тест АВР не втрачаються.
 * - Похідні потоки: [alarms] і [overallLevel] обчислюються з [telemetry] через map,
 *   [canStartTest] — комбінацією [telemetry] та [transferTest] (combine).
 */
class MonitorViewModel(private val repository: TelemetryRepository) : ViewModel() {

    private val _telemetry = MutableStateFlow<TelemetryUiState>(TelemetryUiState.Loading)
    val telemetry: StateFlow<TelemetryUiState> = _telemetry.asStateFlow()

    private val _autoRefresh = MutableStateFlow(false)
    val autoRefresh: StateFlow<Boolean> = _autoRefresh.asStateFlow()

    private val _transferTest = MutableStateFlow<TransferTestState>(TransferTestState.Idle)
    val transferTest: StateFlow<TransferTestState> = _transferTest.asStateFlow()

    /** Тривоги — бізнес-логіка застосовується до кожного нового значення телеметрії. */
    val alarms: StateFlow<List<Alarm>> = telemetry
        .map { state -> state.dataOrNull()?.let(AlarmEvaluator::evaluate).orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val overallLevel: StateFlow<AlarmLevel> = alarms
        .map(AlarmEvaluator::overall)
        .stateIn(viewModelScope, SharingStarted.Eagerly, AlarmLevel.NORMAL)

    /** Тест АВР доступний, коли є свіжі дані, мережа в нормі, достатньо палива і тест ще не йде. */
    val canStartTest: StateFlow<Boolean> = combine(telemetry, transferTest) { state, test ->
        val data = (state as? TelemetryUiState.Success)?.data
        data != null && AlarmEvaluator.canRunTransferTest(data) && test !is TransferTestState.Running
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var updateCount = 0
    private var refreshJob: Job? = null
    private var autoRefreshJob: Job? = null

    init {
        refresh()
    }

    /** Подія View: «Оновити». Повторне натискання під час запиту ігнорується. */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch { load() }
    }

    /** Подія View: увімкнути / вимкнути автоматичне оновлення кожні [AUTO_REFRESH_MS] мс. */
    fun setAutoRefresh(enabled: Boolean) {
        _autoRefresh.value = enabled
        autoRefreshJob?.cancel()
        if (enabled) {
            autoRefreshJob = viewModelScope.launch {
                while (true) {
                    delay(AUTO_REFRESH_MS)
                    if (refreshJob?.isActive != true) load()
                }
            }
        }
    }

    /**
     * Подія View: запустити випробування АВР.
     * Кроки виконуються послідовно в корутині; кожна зміна кроку публікується в [transferTest].
     * Пуск ДГУ має до [TransferTestRules.MAX_START_ATTEMPTS] спроб.
     */
    fun startTransferTest() {
        if (!canStartTest.value) return
        viewModelScope.launch {
            var elapsedMs = 0L
            var attempts = 0
            for (step in TransferStep.entries) {
                if (step == TransferStep.GENERATOR_START) {
                    var started = false
                    while (!started) {
                        attempts++
                        _transferTest.value = TransferTestState.Running(step, attempts)
                        delay(step.durationMs)
                        elapsedMs += step.durationMs
                        started = repository.tryStartGenerator()
                        if (!started && attempts == TransferTestRules.MAX_START_ATTEMPTS) {
                            _transferTest.value = TransferTestState.Failed(
                                step, "ДГУ не запустився після ${TransferTestRules.MAX_START_ATTEMPTS} спроб"
                            )
                            return@launch
                        }
                    }
                } else {
                    _transferTest.value = TransferTestState.Running(step, attempts)
                    delay(step.durationMs)
                    elapsedMs += step.durationMs
                }
            }
            _transferTest.value = TransferTestState.Passed(elapsedMs / 1000.0, attempts)
        }
    }

    /** Подія View: закрити результат тесту. */
    fun resetTransferTest() {
        if (_transferTest.value !is TransferTestState.Running) _transferTest.value = TransferTestState.Idle
    }

    private suspend fun load() {
        val previous = _telemetry.value.dataOrNull()
        _telemetry.value = if (previous == null) {
            TelemetryUiState.Loading
        } else {
            TelemetryUiState.Success(previous, updateCount, refreshing = true)
        }
        _telemetry.value = try {
            val data = repository.fetchTelemetry()
            updateCount++
            TelemetryUiState.Success(data, updateCount)
        } catch (e: IOException) {
            TelemetryUiState.Error(e.message ?: "Помилка зв'язку", previous)
        }
    }

    companion object {
        const val AUTO_REFRESH_MS = 5_000L

        /** Фабрика: ViewModel отримує репозиторій через конструктор (ручне впровадження залежностей). */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { MonitorViewModel(FakeTelemetryRepository()) }
        }
    }
}

/** Останні відомі дані телеметрії незалежно від стану. */
fun TelemetryUiState.dataOrNull(): Telemetry? = when (this) {
    TelemetryUiState.Loading -> null
    is TelemetryUiState.Success -> data
    is TelemetryUiState.Error -> lastData
}
