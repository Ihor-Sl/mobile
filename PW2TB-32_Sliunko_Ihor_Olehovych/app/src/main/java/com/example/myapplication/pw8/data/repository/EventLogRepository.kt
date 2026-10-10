package com.example.myapplication.pw8.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.example.myapplication.pw8.data.local.EventLogDatabase
import com.example.myapplication.pw8.data.local.EventType
import com.example.myapplication.pw8.data.local.EventWithSource
import com.example.myapplication.pw8.data.local.PowerEventEntity
import com.example.myapplication.pw8.data.local.ReserveSourceEntity
import com.example.myapplication.pw8.data.local.SourceKind
import com.example.myapplication.pw8.data.local.SourceStats
import com.example.myapplication.pw8.data.remote.FakeScadaDataSource
import com.example.myapplication.pw8.data.remote.RemoteEventDataSource
import com.example.myapplication.pw8.domain.EventFilter
import java.io.IOException
import kotlinx.coroutines.flow.Flow

/** Результат синхронізації зі SCADA. */
data class SyncReport(val added: Int, val skipped: Int, val newSources: Int, val fromCache: Boolean)

/**
 * Repository — єдина точка доступу до даних журналу для ViewModel.
 *
 * Агрегує два джерела даних:
 *  - локальне (Room) — «єдине джерело правди»: UI завжди читає дані лише з БД через Flow;
 *  - віддалене (SCADA) — події звідти імпортуються в БД під час синхронізації.
 *
 * Кешування: повторна синхронізація протягом [CACHE_TTL_MS] не звертається до мережі.
 * Обробка помилок: мережеві збої перетворюються на Result.failure, а дані в БД залишаються доступними.
 */
class EventLogRepository(
    private val db: EventLogDatabase,
    private val remote: RemoteEventDataSource,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.eventLogDao()

    /** Час останньої успішної синхронізації (кеш у пам'яті). */
    @Volatile
    var lastSyncAt: Long? = null
        private set

    fun observeEvents(filter: EventFilter): Flow<List<EventWithSource>> =
        dao.observeEvents(
            type = filter.type?.name,
            sourceId = filter.sourceId,
            query = filter.query.trim(),
            sort = filter.sort.name,
        )

    fun observeStats(): Flow<List<SourceStats>> = dao.observeStats()

    fun observeSources(): Flow<List<ReserveSourceEntity>> = dao.observeSources()

    suspend fun save(event: PowerEventEntity) {
        if (event.id == 0L) dao.insertEvent(event) else dao.updateEvent(event)
    }

    suspend fun delete(event: PowerEventEntity) = dao.deleteEvent(event)

    /** Повертає видалену подію (кнопка «Скасувати»); зберігається той самий id. */
    suspend fun restore(event: PowerEventEntity) {
        dao.insertEvent(event)
    }

    /**
     * Імпорт подій зі SCADA. Уся операція — одна транзакція: невідомі джерела створюються,
     * а дублікати (вже імпортовані remote_id) пропускаються завдяки унікальному індексу.
     */
    suspend fun sync(force: Boolean = false): Result<SyncReport> {
        val last = lastSyncAt
        if (!force && last != null && now() - last < CACHE_TTL_MS) {
            return Result.success(SyncReport(added = 0, skipped = 0, newSources = 0, fromCache = true))
        }
        return try {
            val remoteEvents = remote.fetchRecentEvents()
            val report = db.withTransaction {
                var newSources = 0
                val entities = remoteEvents.map { r ->
                    val source = dao.findSourceByName(r.sourceName)
                        ?: ReserveSourceEntity(name = r.sourceName, kind = r.sourceKind, ratedPowerKw = r.sourceRatedPowerKw)
                            .let { it.copy(id = dao.insertSource(it)) }
                            .also { newSources++ }
                    PowerEventEntity(
                        sourceId = source.id, type = r.type, startedAt = r.startedAt, durationMin = r.durationMin,
                        avgLoadKw = r.avgLoadKw, fuelUsedL = r.fuelUsedL, note = r.note, remoteId = r.remoteId,
                    )
                }
                val ids = dao.insertEventsIgnoringDuplicates(entities)
                SyncReport(
                    added = ids.count { it != -1L },
                    skipped = ids.count { it == -1L },
                    newSources = newSources,
                    fromCache = false
                )
            }
            lastSyncAt = now()
            Result.success(report)
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /** Початкове наповнення порожньої бази демонстраційними даними (в одній транзакції). */
    suspend fun ensureSeeded() {
        db.withTransaction {
            if (dao.countSources() > 0) return@withTransaction
            val t = now()
            fun event(type: EventType, hoursAgo: Long, minutes: Int, load: Double, fuel: Double, note: String) =
                PowerEventEntity(
                    sourceId = 0, type = type, startedAt = t - hoursAgo * HOUR, durationMin = minutes,
                    avgLoadKw = load, fuelUsedL = fuel, note = note,
                )
            dao.insertSourceWithEvents(
                ReserveSourceEntity(name = "ДГУ-1 Лікарня", kind = SourceKind.GENERATOR, ratedPowerKw = 160.0),
                listOf(
                    event(EventType.OUTAGE, 200, 180, 112.0, 76.0, "Пошкодження кабельної лінії, ремонт РЕМ"),
                    event(EventType.TEST, 120, 30, 60.0, 8.5, "Щомісячне випробування під навантаженням"),
                    event(EventType.MAINTENANCE, 90, 45, 20.0, 3.0, "Заміна оливи та фільтрів"),
                )
            )
            dao.insertSourceWithEvents(
                ReserveSourceEntity(name = "ДГУ-2 Насосна", kind = SourceKind.GENERATOR, ratedPowerKw = 80.0),
                listOf(
                    event(EventType.OUTAGE, 160, 320, 64.0, 88.0, "Графікове відключення, 2 черга"),
                    event(EventType.TEST, 40, 25, 35.0, 4.2, "Тест АВР"),
                )
            )
            dao.insertSourceWithEvents(
                ReserveSourceEntity(name = "ДБЖ ЦОД", kind = SourceKind.UPS, ratedPowerKw = 200.0),
                listOf(
                    event(EventType.OUTAGE, 160, 6, 170.0, 0.0, "Перехід на АКБ до пуску ДГУ"),
                    event(EventType.TEST, 64, 15, 120.0, 0.0, "Калібрування АКБ"),
                )
            )
        }
    }

    companion object {
        const val CACHE_TTL_MS = 30_000L
        private const val HOUR = 3_600_000L

        fun create(context: Context) =
            EventLogRepository(EventLogDatabase.getInstance(context), FakeScadaDataSource())
    }
}
