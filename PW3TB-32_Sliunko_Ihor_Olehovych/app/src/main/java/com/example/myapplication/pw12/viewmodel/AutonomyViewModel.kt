package com.example.myapplication.pw12.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myapplication.pw12.data.AutonomyForecast
import com.example.myapplication.pw12.data.AutonomyRepository
import com.example.myapplication.pw12.data.ForecastInput
import com.example.myapplication.pw12.data.remote.FaultyAutonomyServer
import com.example.myapplication.pw12.data.remote.FaultScenario
import com.example.myapplication.pw12.errors.AppError
import com.example.myapplication.pw12.errors.AppException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Стан запиту, який відображає UI. */
sealed interface RequestState {
    data object Idle : RequestState

    /** [attempt] із [maxAttempts]; [retryInSec] — зворотний відлік до повтору; [lastError] — причина повтору. */
    data class Loading(val attempt: Int, val maxAttempts: Int, val retryInSec: Int? = null, val lastError: AppError? = null) : RequestState
    data class Success(val forecast: AutonomyForecast, val attempts: Int) : RequestState
    data class Failed(val error: AppError, val attempts: Int, val showDialog: Boolean = true) : RequestState
}

data class InputState(
    val code: String = "OBJ-03",
    val hours: String = "12",
    val codeError: String? = null,
    val hoursError: String? = null,
)

/** Одноразове повідомлення для Snackbar. */
data class Notice(val text: String, val id: Long = System.nanoTime())

/**
 * ViewModel ПЗ 12: передає інформацію про помилки з Repository в UI через StateFlow.
 *
 *  - тимчасові помилки (під час повторів) → короткі повідомлення у Snackbar ([notice]);
 *  - остаточні помилки → стан [RequestState.Failed], який UI показує діалогом з кнопкою «Повторити»;
 *  - непередбачені винятки перехоплює [CoroutineExceptionHandler] — застосунок не завершується аварійно,
 *    а помилка також потрапляє в журнал.
 */
class AutonomyViewModel(
    private val repository: AutonomyRepository,
    private val server: FaultyAutonomyServer? = null,
) : ViewModel() {

    private val _input = MutableStateFlow(InputState())
    val input: StateFlow<InputState> = _input.asStateFlow()

    private val _request = MutableStateFlow<RequestState>(RequestState.Idle)
    val request: StateFlow<RequestState> = _request.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    private val _log = MutableStateFlow(repository.logger.recent(8))
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _scenario = MutableStateFlow(server?.scenario ?: FaultScenario.NORMAL)
    val scenario: StateFlow<FaultScenario> = _scenario.asStateFlow()

    /** Стан підключення в реальному часі (банер «Немає з'єднання»). */
    val online: StateFlow<Boolean> = repository.network.online
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private var job: Job? = null

    /** Останній рубіж: будь-який неочікуваний виняток перетворюється на стан помилки. */
    private val crashGuard = CoroutineExceptionHandler { _, throwable ->
        val error = repository.handler.handle(throwable, "ViewModel")
        _request.value = RequestState.Failed(error, attempts = 0)
        refreshLog()
    }

    fun onCodeChange(v: String) = _input.update { it.copy(code = v.uppercase(), codeError = null) }
    fun onHoursChange(v: String) = _input.update { it.copy(hours = v.filter(Char::isDigit).take(3), hoursError = null) }

    fun setScenario(s: FaultScenario) {
        server?.scenario = s
        _scenario.value = s
    }

    fun requestForecast() {
        val i = _input.value
        val codeErr = ForecastInput.codeError(i.code)
        val hoursErr = ForecastInput.hoursError(i.hours)
        if (codeErr != null || hoursErr != null) {
            _input.update { it.copy(codeError = codeErr, hoursError = hoursErr) }
            _notice.value = Notice("Виправте введені дані — запит не надіслано")
            return
        }
        job?.cancel()
        var attempts = 0
        val max = repository.retryPolicy.maxAttempts
        job = viewModelScope.launch(crashGuard) {
            _request.value = RequestState.Loading(1, max)
            try {
                val forecast = repository.forecast(
                    code = i.code.trim(),
                    hours = i.hours.trim().toInt(),
                    onAttempt = { n ->
                        attempts = n
                        _request.update { s -> if (s is RequestState.Loading) s.copy(attempt = n, retryInSec = null) else RequestState.Loading(n, max) }
                    },
                    onRetry = { info ->
                        refreshLog()
                        _notice.value = Notice("${info.error.title}: ${info.error.userMessage}")
                        _request.value = RequestState.Loading(info.attempt, max, lastError = info.error)
                    },
                    // Зворотний відлік до наступної спроби — користувач бачить, що застосунок не «завис»
                    onCountdown = { sec -> _request.update { s -> if (s is RequestState.Loading) s.copy(retryInSec = sec) else s } },
                )
                _request.value = RequestState.Success(forecast, attempts)
                if (attempts > 1) _notice.value = Notice("Успіх з ${attempts}-ї спроби")
            } catch (e: AppException) {
                _request.value = RequestState.Failed(e.error, attempts)
            } finally {
                refreshLog()
            }
        }
    }

    /** Закрити діалог помилки (повідомлення лишається в картці стану). */
    fun dismissError() {
        _request.update { if (it is RequestState.Failed) it.copy(showDialog = false) else it }
    }

    fun noticeShown() {
        _notice.value = null
    }

    fun clearLog() {
        repository.logger.clear()
        refreshLog()
    }

    private fun refreshLog() {
        _log.value = repository.logger.recent(8)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val (repository, server) = AutonomyRepository.create(checkNotNull(this[APPLICATION_KEY]))
                AutonomyViewModel(repository, server)
            }
        }
    }
}
