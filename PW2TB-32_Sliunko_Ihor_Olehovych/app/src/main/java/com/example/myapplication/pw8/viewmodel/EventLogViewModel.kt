package com.example.myapplication.pw8.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myapplication.pw8.data.local.EventType
import com.example.myapplication.pw8.data.local.EventWithSource
import com.example.myapplication.pw8.data.local.PowerEventEntity
import com.example.myapplication.pw8.data.local.ReserveSourceEntity
import com.example.myapplication.pw8.data.local.SourceStats
import com.example.myapplication.pw8.data.repository.EventLogRepository
import com.example.myapplication.pw8.domain.EventDraft
import com.example.myapplication.pw8.domain.EventField
import com.example.myapplication.pw8.domain.EventFilter
import com.example.myapplication.pw8.domain.EventSort
import com.example.myapplication.pw8.domain.EventValidator
import com.example.myapplication.pw8.domain.LogSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Стан синхронізації зі SCADA. */
sealed interface SyncState {
    data object Idle : SyncState
    data object Syncing : SyncState
    data class Done(val message: String) : SyncState
    data class Error(val message: String) : SyncState
}

/** Повідомлення для snackbar; [undoEvent] — подія, яку можна повернути. */
data class UiMessage(val text: String, val undoEvent: PowerEventEntity? = null)

/**
 * ViewModel журналу подій (MVVM + Repository + Room).
 *
 * Список подій — це Flow з Room, «перемкнутий» на поточний фільтр через flatMapLatest:
 * при зміні фільтра попередній запит скасовується і підписка йде на новий,
 * а будь-яка зміна таблиці (додавання, редагування, видалення, синхронізація) автоматично
 * надходить у UI без ручного перезавантаження.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventLogViewModel(private val repository: EventLogRepository) : ViewModel() {

    private val _filter = MutableStateFlow(EventFilter())
    val filter: StateFlow<EventFilter> = _filter.asStateFlow()

    val events: StateFlow<List<EventWithSource>> = _filter
        .flatMapLatest { repository.observeEvents(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Підсумок рахується по відфільтрованому списку — змінюється разом із фільтром. */
    val summary: StateFlow<LogSummary> = events
        .map(LogSummary::of)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogSummary())

    val stats: StateFlow<List<SourceStats>> = repository.observeStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sources: StateFlow<List<ReserveSourceEntity>> = repository.observeSources()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    init {
        viewModelScope.launch { repository.ensureSeeded() }
    }

    // ---------- Фільтрація та сортування ----------

    fun setType(type: EventType?) = _filter.update { it.copy(type = type) }

    /** Повторне натискання на вибране джерело знімає фільтр. */
    fun toggleSource(sourceId: Long) =
        _filter.update { it.copy(sourceId = if (it.sourceId == sourceId) null else sourceId) }

    fun setQuery(query: String) = _filter.update { it.copy(query = query) }

    fun setSort(sort: EventSort) = _filter.update { it.copy(sort = sort) }

    fun clearFilters() = _filter.update { EventFilter(sort = it.sort) }

    // ---------- CRUD ----------

    /**
     * Перевіряє чернетку та, якщо помилок немає, асинхронно зберігає подію (insert або update).
     * Повертає помилки полів — діалог показує їх одразу.
     */
    fun save(draft: EventDraft, now: Long = System.currentTimeMillis()): Map<EventField, String> {
        val source = sources.value.firstOrNull { it.id == draft.sourceId }
        val errors = EventValidator.validate(draft, source, now)
        if (errors.isEmpty()) {
            viewModelScope.launch {
                repository.save(EventValidator.toEntity(draft))
                _message.value = UiMessage(if (draft.id == 0L) "Подію додано" else "Зміни збережено")
            }
        }
        return errors
    }

    fun delete(event: PowerEventEntity) {
        viewModelScope.launch {
            repository.delete(event)
            _message.value = UiMessage("Подію видалено", undoEvent = event)
        }
    }

    fun undoDelete(event: PowerEventEntity) {
        viewModelScope.launch { repository.restore(event) }
    }

    fun messageShown() {
        _message.value = null
    }

    // ---------- Синхронізація ----------

    fun sync(force: Boolean = false) {
        if (_syncState.value == SyncState.Syncing) return
        _syncState.value = SyncState.Syncing
        viewModelScope.launch {
            repository.sync(force)
                .onSuccess { report ->
                    _syncState.value = SyncState.Done(
                        if (report.fromCache) "Дані актуальні (синхронізовано менш ніж 30 с тому)"
                        else "Імпортовано ${report.added}, дублікатів пропущено ${report.skipped}" +
                            if (report.newSources > 0) ", нових джерел ${report.newSources}" else ""
                    )
                }
                .onFailure { error ->
                    _syncState.value = SyncState.Error("${error.message}. Показано локальні дані.")
                }
        }
    }

    companion object {
        /** Фабрика отримує Application, щоб створити репозиторій з БД Room. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                EventLogViewModel(EventLogRepository.create(app))
            }
        }
    }
}
