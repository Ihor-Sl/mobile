package com.example.myapplication.pw8.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * DAO журналу подій.
 *
 * - suspend-функції виконуються Room у фоновому потоці (не блокують UI);
 * - функції, що повертають Flow, «реактивні»: Room повторно виконує запит і надсилає новий список
 *   щоразу, коли змінюється таблиця — UI оновлюється сам, без ручного перезавантаження.
 */
@Dao
interface EventLogDao {

    // ---------- Джерела ----------

    @Insert
    suspend fun insertSource(source: ReserveSourceEntity): Long

    @Query("SELECT * FROM reserve_sources ORDER BY name")
    fun observeSources(): Flow<List<ReserveSourceEntity>>

    @Query("SELECT * FROM reserve_sources WHERE name = :name LIMIT 1")
    suspend fun findSourceByName(name: String): ReserveSourceEntity?

    @Query("SELECT COUNT(*) FROM reserve_sources")
    suspend fun countSources(): Int

    @Delete
    suspend fun deleteSource(source: ReserveSourceEntity)

    /** Джерело з усіма подіями (@Relation); @Transaction гарантує узгодженість двох запитів. */
    @Transaction
    @Query("SELECT * FROM reserve_sources WHERE id = :sourceId")
    suspend fun getSourceWithEvents(sourceId: Long): SourceWithEvents?

    // ---------- Події: CRUD ----------

    @Insert
    suspend fun insertEvent(event: PowerEventEntity): Long

    /** Вставка з ігноруванням дублікатів за унікальним remote_id; для пропущених рядків повертає -1. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEventsIgnoringDuplicates(events: List<PowerEventEntity>): List<Long>

    @Update
    suspend fun updateEvent(event: PowerEventEntity)

    @Delete
    suspend fun deleteEvent(event: PowerEventEntity)

    @Query("SELECT COUNT(*) FROM power_events")
    suspend fun countEvents(): Int

    /**
     * Пошук, фільтрація та сортування на рівні SQL.
     * Параметр null означає «без фільтра»; порядок сортування обирається через CASE.
     * energy_kwh та load_percent — обчислювані поля.
     */
    @Query(
        """
        SELECT e.*, s.name AS source_name, s.kind AS source_kind,
               e.avg_load_kw * e.duration_min / 60.0 AS energy_kwh,
               e.avg_load_kw * 100.0 / s.rated_power_kw AS load_percent
        FROM power_events e
        INNER JOIN reserve_sources s ON s.id = e.source_id
        WHERE (:type IS NULL OR e.type = :type)
          AND (:sourceId IS NULL OR e.source_id = :sourceId)
          AND (:query = '' OR e.note LIKE '%' || :query || '%' OR s.name LIKE '%' || :query || '%')
        ORDER BY
          CASE WHEN :sort = 'DATE_DESC' THEN e.started_at END DESC,
          CASE WHEN :sort = 'DATE_ASC' THEN e.started_at END ASC,
          CASE WHEN :sort = 'DURATION_DESC' THEN e.duration_min END DESC,
          CASE WHEN :sort = 'LOAD_DESC' THEN e.avg_load_kw END DESC,
          e.started_at DESC
        """
    )
    fun observeEvents(type: String?, sourceId: Long?, query: String, sort: String): Flow<List<EventWithSource>>

    /** Агрегація по джерелах: LEFT JOIN, щоб у статистиці були й джерела без подій. */
    @Query(
        """
        SELECT s.id AS source_id, s.name, s.kind, s.rated_power_kw,
               COUNT(e.id) AS event_count,
               COALESCE(SUM(CASE WHEN e.type = 'OUTAGE' THEN 1 ELSE 0 END), 0) AS outage_count,
               COALESCE(SUM(e.duration_min), 0) / 60.0 AS total_hours,
               COALESCE(SUM(e.avg_load_kw * e.duration_min / 60.0), 0) AS total_energy_kwh,
               COALESCE(SUM(e.fuel_used_l), 0) AS total_fuel_l,
               COALESCE(AVG(e.avg_load_kw), 0) * 100.0 / s.rated_power_kw AS avg_load_percent
        FROM reserve_sources s
        LEFT JOIN power_events e ON e.source_id = s.id
        GROUP BY s.id
        ORDER BY total_hours DESC
        """
    )
    fun observeStats(): Flow<List<SourceStats>>

    /**
     * Транзакція: джерело та його події вставляються атомарно — або все, або нічого.
     * Події отримують id щойно створеного джерела.
     */
    @Transaction
    suspend fun insertSourceWithEvents(source: ReserveSourceEntity, events: List<PowerEventEntity>): Long {
        val sourceId = insertSource(source)
        events.forEach { insertEvent(it.copy(sourceId = sourceId)) }
        return sourceId
    }
}
