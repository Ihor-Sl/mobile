package com.example.myapplication.pw8.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** Тип резервного джерела. Room зберігає enum у стовпці як рядок (назва елемента). */
enum class SourceKind(val title: String) {
    GENERATOR("ДГУ"),
    UPS("ДБЖ"),
}

/** Тип події в журналі. */
enum class EventType(val title: String) {
    OUTAGE("Аварія мережі"),
    TEST("Випробування"),
    MAINTENANCE("Обслуговування"),
}

/**
 * Таблиця резервних джерел (батьківська таблиця зв'язку «один-до-багатьох»).
 * Назва джерела унікальна — за нею зіставляються події, що надходять зі SCADA.
 */
@Entity(
    tableName = "reserve_sources",
    indices = [Index(value = ["name"], unique = true)]
)
data class ReserveSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: SourceKind,
    @ColumnInfo(name = "rated_power_kw") val ratedPowerKw: Double,
)

/**
 * Таблиця подій: кожна подія належить одному джерелу (зовнішній ключ source_id).
 * ON DELETE CASCADE — при видаленні джерела видаляються і всі його події.
 * Індекси прискорюють фільтрацію за джерелом і сортування за датою;
 * унікальний remote_id не дає імпортувати ту саму подію зі SCADA двічі.
 */
@Entity(
    tableName = "power_events",
    foreignKeys = [
        ForeignKey(
            entity = ReserveSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("source_id"),
        Index("started_at"),
        Index(value = ["remote_id"], unique = true),
    ]
)
data class PowerEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    val type: EventType,
    /** Початок події, мілісекунди від 01.01.1970. */
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "duration_min") val durationMin: Int,
    @ColumnInfo(name = "avg_load_kw") val avgLoadKw: Double,
    @ColumnInfo(name = "fuel_used_l") val fuelUsedL: Double,
    val note: String,
    /** Ідентифікатор події у SCADA; null — подію внесено вручну. */
    @ColumnInfo(name = "remote_id") val remoteId: String? = null,
)

/**
 * Подія разом із даними джерела (результат JOIN) та обчислюваними полями,
 * які рахує сама SQLite: енергія, віддана споживачам, і завантаження джерела у відсотках.
 */
data class EventWithSource(
    @Embedded val event: PowerEventEntity,
    @ColumnInfo(name = "source_name") val sourceName: String,
    @ColumnInfo(name = "source_kind") val sourceKind: SourceKind,
    @ColumnInfo(name = "energy_kwh") val energyKwh: Double,
    @ColumnInfo(name = "load_percent") val loadPercent: Double,
)

/** Агрегована статистика по джерелу (GROUP BY у запиті DAO). */
data class SourceStats(
    @ColumnInfo(name = "source_id") val sourceId: Long,
    val name: String,
    val kind: SourceKind,
    @ColumnInfo(name = "rated_power_kw") val ratedPowerKw: Double,
    @ColumnInfo(name = "event_count") val eventCount: Int,
    @ColumnInfo(name = "outage_count") val outageCount: Int,
    @ColumnInfo(name = "total_hours") val totalHours: Double,
    @ColumnInfo(name = "total_energy_kwh") val totalEnergyKwh: Double,
    @ColumnInfo(name = "total_fuel_l") val totalFuelL: Double,
    @ColumnInfo(name = "avg_load_percent") val avgLoadPercent: Double,
)

/** Зв'язок «один-до-багатьох» через @Relation: джерело та всі його події. */
data class SourceWithEvents(
    @Embedded val source: ReserveSourceEntity,
    @Relation(parentColumn = "id", entityColumn = "source_id")
    val events: List<PowerEventEntity>,
)
