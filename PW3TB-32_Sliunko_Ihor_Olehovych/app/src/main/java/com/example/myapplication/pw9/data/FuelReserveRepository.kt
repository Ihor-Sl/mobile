package com.example.myapplication.pw9.data

import android.content.Context
import com.example.myapplication.net.buildRetrofit
import com.example.myapplication.pw9.data.local.FuelReserveDatabase
import com.example.myapplication.pw9.data.local.LocalTankDataSource
import com.example.myapplication.pw9.data.local.RoomTankDataSource
import com.example.myapplication.pw9.data.remote.MockTankServer
import com.example.myapplication.pw9.data.remote.RemoteTankDataSource
import com.example.myapplication.pw9.data.remote.RetrofitTankDataSource
import com.example.myapplication.pw9.data.remote.TankApi
import com.example.myapplication.pw9.domain.FuelTank
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** Звідки ViewModel бере дані. */
enum class DataMode(val label: String) {
    LOCAL("Локально"),
    REMOTE("Сервер"),
    AGGREGATED("Агреговано"),
}

/** Результат запиту до сервера в режимі REMOTE. */
data class RemoteSnapshot(val tanks: List<FuelTank>, val fetchedAt: Long, val fromCache: Boolean)

/** Підсумок синхронізації в режимі AGGREGATED. */
data class SyncReport(val pushed: Int, val updated: Int, val added: Int, val fromCache: Boolean)

/** Чи вдалося одразу передати зміну на сервер. */
data class SaveOutcome(val tank: FuelTank, val syncedWithServer: Boolean)

/**
 * Repository — єдина точка доступу до даних про резерви палива.
 *
 * Агрегує два Data Source:
 *  - локальний ([LocalTankDataSource], Room) — працює без мережі і є кешем для агрегованого режиму;
 *  - віддалений ([RemoteTankDataSource], Retrofit) — сервер диспетчерської зі свіжою телеметрією.
 *
 * Архітектурні рішення:
 *  - Режим AGGREGATED: UI завжди читає Room (offline-first), а [refresh] підтягує серверні дані.
 *    При об'єднанні перемагає новіший запис (updated_at), але локальні невідправлені зміни
 *    (pending_sync) сервер не перезаписує — спочатку вони відправляються на сервер.
 *  - Кешування: повторна синхронізація протягом [cacheTtlMs] не звертається до мережі;
 *    відповідь сервера для режиму REMOTE зберігається в пам'яті на той самий час.
 *  - Обробка помилок: збої мережі та HTTP перетворюються на Result.failure, локальні дані залишаються.
 *  - Асинхронність: усі операції — suspend-функції на [io]-диспетчері; [mutex] не дає двом
 *    синхронізаціям виконуватися одночасно (наприклад, при швидкому повторному натисканні).
 */
class FuelReserveRepository(
    private val local: LocalTankDataSource,
    private val remote: RemoteTankDataSource,
    private val now: () -> Long = System::currentTimeMillis,
    private val cacheTtlMs: Long = 60_000,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    @Volatile
    private var lastSyncAt: Long? = null

    @Volatile
    private var remoteCache: RemoteSnapshot? = null

    /** Час останньої успішної синхронізації з сервером. */
    val lastSyncTime: Long? get() = lastSyncAt

    /** Дані локального сховища; Flow оновлюється після кожного запису в Room. */
    fun observeLocal(): Flow<List<FuelTank>> = local.observeTanks()

    /** Режим REMOTE: дані лише із сервера (без запису в БД) з кешем у пам'яті. */
    suspend fun fetchRemote(force: Boolean = false): Result<RemoteSnapshot> = withContext(io) {
        val cached = remoteCache
        if (!force && cached != null && now() - cached.fetchedAt < cacheTtlMs) {
            return@withContext Result.success(cached.copy(fromCache = true))
        }
        runRemote {
            RemoteSnapshot(remote.fetchAll(), now(), fromCache = false).also { remoteCache = it }
        }
    }

    /** Режим AGGREGATED: відправити локальні зміни, отримати серверні та об'єднати їх у Room. */
    suspend fun refresh(force: Boolean = false): Result<SyncReport> = mutex.withLock {
        withContext(io) {
            val last = lastSyncAt
            if (!force && last != null && now() - last < cacheTtlMs) {
                return@withContext Result.success(SyncReport(0, 0, 0, fromCache = true))
            }
            runRemote {
                val pushed = pushPending()
                val remoteTanks = remote.fetchAll()
                val stored = local.getAll().associateBy { it.tank.id }
                var updated = 0
                var added = 0
                val changes = remoteTanks.mapNotNull { r ->
                    val mine = stored[r.id]
                    when {
                        mine == null -> r.also { added++ }
                        // Локальне видалення / невідправлена зміна мають пріоритет
                        mine.deleted || mine.tank.pendingSync -> null
                        r.updatedAt > mine.tank.updatedAt -> r.also { updated++ }
                        else -> null
                    }
                }
                local.save(changes)
                lastSyncAt = now()
                remoteCache = RemoteSnapshot(remoteTanks, now(), fromCache = false)
                SyncReport(pushed, updated, added, fromCache = false)
            }
        }
    }

    /**
     * Додавання або оновлення. Запис спочатку потрапляє в Room (UI оновлюється одразу),
     * потім відправляється на сервер; якщо сервер недоступний — лишається з прапорцем pending_sync.
     */
    suspend fun save(tank: FuelTank): SaveOutcome = withContext(io) {
        val pending = tank.copy(updatedAt = now(), pendingSync = true)
        local.save(listOf(pending))
        remoteCache = null
        val synced = try {
            remote.upsert(pending.copy(pendingSync = false))
            local.save(listOf(pending.copy(pendingSync = false)))
            true
        } catch (e: IOException) {
            false
        } catch (e: HttpException) {
            false
        }
        SaveOutcome(pending.copy(pendingSync = !synced), synced)
    }

    /** Видалення: локально — одразу (tombstone), на сервері — зараз або при наступній синхронізації. */
    suspend fun delete(id: String): Boolean = withContext(io) {
        local.markDeleted(id)
        remoteCache = null
        try {
            remote.delete(id)
            local.remove(id)
            true
        } catch (e: IOException) {
            false
        } catch (e: HttpException) {
            false
        }
    }

    /** Повернення видаленого запису (кнопка «Скасувати»). */
    suspend fun restore(tank: FuelTank) = save(tank)

    /** Генератор ідентифікаторів TNK-NN, унікальний серед локальних записів. */
    suspend fun nextId(): String = withContext(io) {
        val max = local.getAll().mapNotNull { it.tank.id.removePrefix("TNK-").toIntOrNull() }.maxOrNull() ?: 0
        "TNK-%02d".format(max + 1)
    }

    /** Початкові дані: локальна БД заповнюється «вчорашніми» показниками (до першої синхронізації). */
    suspend fun ensureSeeded() = withContext(io) {
        if (!local.isEmpty()) return@withContext
        val t = now() - 3 * 3_600_000L
        local.save(
            listOf(
                FuelTank("TNK-01", "ДГУ-1", "Лікарня № 3", 1200.0, 1000.0, 160.0, 120.0, 38.0, t),
                FuelTank("TNK-02", "ДГУ-2", "Насосна станція", 500.0, 300.0, 80.0, 55.0, 22.0, t),
                FuelTank("TNK-03", "ДГУ-3", "ЦОД «Енергохаб»", 1000.0, 800.0, 250.0, 180.0, 60.0, t),
                FuelTank("TNK-04", "ДГУ-4", "Котельня", 600.0, 90.0, 100.0, 70.0, 27.0, t),
                FuelTank("TNK-05", "ДГУ-5", "Пожежна частина", 300.0, 260.0, 40.0, 22.0, 11.0, t),
            )
        )
    }

    /** Відправляє на сервер усі локальні зміни, що ще не синхронізовані. */
    private suspend fun pushPending(): Int {
        var pushed = 0
        for (stored in local.getAll().filter { it.deleted || it.tank.pendingSync }) {
            if (stored.deleted) {
                remote.delete(stored.tank.id)
                local.remove(stored.tank.id)
            } else {
                remote.upsert(stored.tank.copy(pendingSync = false))
                local.save(listOf(stored.tank.copy(pendingSync = false)))
            }
            pushed++
        }
        return pushed
    }

    /** Єдине місце перетворення мережевих винятків на Result.failure. */
    private inline fun <T> runRemote(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: IOException) {
        Result.failure(e)
    } catch (e: HttpException) {
        Result.failure(e)
    }

    companion object {
        /** Ручне впровадження залежностей: один сервер і один Repository на весь застосунок. */
        @Volatile
        private var instance: Pair<FuelReserveRepository, MockTankServer>? = null

        fun create(context: Context): Pair<FuelReserveRepository, MockTankServer> =
            instance ?: synchronized(this) {
                instance ?: run {
                    val server = MockTankServer()
                    val api = buildRetrofit(server).create(TankApi::class.java)
                    val db = FuelReserveDatabase.getInstance(context)
                    FuelReserveRepository(RoomTankDataSource(db.tankDao()), RetrofitTankDataSource(api)) to server
                }.also { instance = it }
            }
    }
}
