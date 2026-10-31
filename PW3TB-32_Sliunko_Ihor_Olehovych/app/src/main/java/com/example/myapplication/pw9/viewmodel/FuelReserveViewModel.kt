package com.example.myapplication.pw9.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myapplication.pw9.data.DataMode
import com.example.myapplication.pw9.data.FuelReserveRepository
import com.example.myapplication.pw9.data.remote.MockTankServer
import com.example.myapplication.pw9.data.remote.describeRemoteError
import com.example.myapplication.pw9.domain.FleetSummary
import com.example.myapplication.pw9.domain.FuelTank
import com.example.myapplication.pw9.domain.ReserveStatus
import com.example.myapplication.pw9.domain.TankDraft
import com.example.myapplication.pw9.domain.TankField
import com.example.myapplication.pw9.domain.TankFilter
import com.example.myapplication.pw9.domain.TankSort
import com.example.myapplication.pw9.domain.TankValidator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Стан джерела даних, який бачить UI. */
data class SourceState(
    val loading: Boolean = false,
    val origin: String = "",
    val updatedAt: Long? = null,
    val error: String? = null,
)

/** Одноразове повідомлення для Snackbar; [undo] — видалений запис, який можна повернути. */
data class ReserveMessage(val text: String, val undo: FuelTank? = null)

/**
 * ViewModel екрана резервів палива (MVVM).
 *
 * ViewModel не знає ні про Room, ні про Retrofit — лише про [FuelReserveRepository].
 * Список, який бачить UI, будується «реактивно»:
 *  режим → (Room Flow або знімок сервера) → combine з фільтром → StateFlow для Compose.
 * Будь-який запис у Room (додавання, редагування, видалення, синхронізація) сам надходить у UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FuelReserveViewModel(
    private val repository: FuelReserveRepository,
    private val server: MockTankServer? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _mode = MutableStateFlow(DataMode.AGGREGATED)
    val mode: StateFlow<DataMode> = _mode.asStateFlow()

    private val _filter = MutableStateFlow(TankFilter())
    val filter: StateFlow<TankFilter> = _filter.asStateFlow()

    /** Останній знімок сервера для режиму REMOTE. */
    private val remoteTanks = MutableStateFlow<List<FuelTank>>(emptyList())

    /** Повний список з обраного джерела: flatMapLatest скасовує попередню підписку при зміні режиму. */
    val tanks: StateFlow<List<FuelTank>> = _mode
        .flatMapLatest { if (it == DataMode.REMOTE) remoteTanks else repository.observeLocal() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Відфільтрований і відсортований список для LazyColumn. */
    val visible: StateFlow<List<FuelTank>> = combine(tanks, _filter) { list, f -> f.apply(list) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Агреговані показники рахуються по всіх записах джерела (не залежать від фільтра). */
    val summary: StateFlow<FleetSummary> = tanks.map(FleetSummary::of)
        .stateIn(viewModelScope, SharingStarted.Eagerly, FleetSummary())

    private val _source = MutableStateFlow(SourceState())
    val source: StateFlow<SourceState> = _source.asStateFlow()

    private val _message = MutableStateFlow<ReserveMessage?>(null)
    val message: StateFlow<ReserveMessage?> = _message.asStateFlow()

    private val _serverOffline = MutableStateFlow(server?.offline ?: false)
    val serverOffline: StateFlow<Boolean> = _serverOffline.asStateFlow()

    init {
        viewModelScope.launch {
            repository.ensureSeeded()
            load(force = false)
        }
    }

    // ---------- Режим джерела даних ----------

    fun setMode(mode: DataMode) {
        if (_mode.value == mode) return
        _mode.value = mode
        load(force = false)
    }

    /** Кнопка «Оновити» — примусовий запит до сервера в обхід кешу. */
    fun refresh() = load(force = true)

    private fun load(force: Boolean) {
        when (_mode.value) {
            DataMode.LOCAL -> _source.value = SourceState(origin = "Локальна БД Room (без мережі)")
            DataMode.REMOTE -> viewModelScope.launch {
                _source.update { it.copy(loading = true, error = null) }
                repository.fetchRemote(force)
                    .onSuccess { snap ->
                        remoteTanks.value = snap.tanks
                        _source.value = SourceState(
                            origin = if (snap.fromCache) "Сервер диспетчерської (кеш у пам'яті)" else "Сервер диспетчерської (Retrofit)",
                            updatedAt = snap.fetchedAt,
                        )
                    }
                    .onFailure { e ->
                        _source.value = SourceState(origin = "Сервер диспетчерської", error = describeRemoteError(e))
                    }
            }
            DataMode.AGGREGATED -> viewModelScope.launch {
                _source.update { it.copy(loading = true, error = null) }
                repository.refresh(force)
                    .onSuccess { r ->
                        _source.value = SourceState(
                            origin = if (r.fromCache) "Room + сервер (кеш актуальний, запиту не було)"
                            else "Room + сервер: оновлено ${r.updated}, додано ${r.added}, відправлено ${r.pushed}",
                            updatedAt = repository.lastSyncTime,
                        )
                    }
                    .onFailure { e ->
                        _source.value = SourceState(
                            origin = "Room (кеш)",
                            updatedAt = repository.lastSyncTime,
                            error = describeRemoteError(e) + ". Показано локальні дані.",
                        )
                    }
            }
        }
    }

    /** Імітація втрати зв'язку із сервером (для демонстрації обробки помилок і кешу). */
    fun setServerOffline(offline: Boolean) {
        server?.offline = offline
        _serverOffline.value = offline
    }

    // ---------- Фільтрація та сортування ----------

    fun setQuery(query: String) = _filter.update { it.copy(query = query) }

    /** Повторне натискання на той самий статус знімає фільтр. */
    fun toggleStatus(status: ReserveStatus?) =
        _filter.update { it.copy(status = if (it.status == status) null else status) }

    fun setSort(sort: TankSort) = _filter.update { it.copy(sort = sort) }

    // ---------- CRUD ----------

    /** Перевіряє форму; якщо помилок немає — асинхронно зберігає через Repository. */
    fun save(draft: TankDraft): Map<TankField, String> {
        val otherNames = tanks.value.filter { it.id != draft.id }.map { it.name }
        val errors = TankValidator.validate(draft, otherNames)
        if (errors.isNotEmpty()) return errors
        viewModelScope.launch {
            val id = draft.id ?: repository.nextId()
            val outcome = repository.save(TankValidator.toTank(draft, id, now()))
            _message.value = ReserveMessage(
                (if (draft.id == null) "Резервуар додано" else "Зміни збережено") +
                    if (outcome.syncedWithServer) " і передано на сервер" else " локально — сервер недоступний, відправимо пізніше"
            )
            if (_mode.value == DataMode.REMOTE) load(force = true)
        }
        return emptyMap()
    }

    fun delete(tank: FuelTank) {
        viewModelScope.launch {
            val synced = repository.delete(tank.id)
            _message.value = ReserveMessage(
                if (synced) "${tank.name} видалено" else "${tank.name} видалено локально, сервер оновиться при синхронізації",
                undo = tank,
            )
            if (_mode.value == DataMode.REMOTE) load(force = true)
        }
    }

    fun undoDelete(tank: FuelTank) {
        viewModelScope.launch { repository.restore(tank) }
    }

    fun messageShown() {
        _message.value = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val (repository, server) = FuelReserveRepository.create(checkNotNull(this[APPLICATION_KEY]))
                FuelReserveViewModel(repository, server)
            }
        }
    }
}
