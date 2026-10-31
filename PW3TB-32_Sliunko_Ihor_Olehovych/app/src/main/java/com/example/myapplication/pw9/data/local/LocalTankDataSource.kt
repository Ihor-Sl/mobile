package com.example.myapplication.pw9.data.local

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.example.myapplication.pw9.domain.FuelTank
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Рядок таблиці fuel_tanks. Окрім даних резервуара зберігає службові прапорці синхронізації:
 * pending_sync — локальна зміна ще не відправлена на сервер;
 * deleted — «надгробок» (tombstone): запис видалено локально, але сервер про це ще не знає.
 */
@Entity(tableName = "fuel_tanks")
data class FuelTankEntity(
    @PrimaryKey val id: String,
    val name: String,
    val facility: String,
    @ColumnInfo(name = "capacity_l") val capacityL: Double,
    @ColumnInfo(name = "level_l") val levelL: Double,
    @ColumnInfo(name = "rated_power_kw") val ratedPowerKw: Double,
    @ColumnInfo(name = "load_kw") val loadKw: Double,
    @ColumnInfo(name = "full_load_lph") val fullLoadLph: Double,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "pending_sync") val pendingSync: Boolean = false,
    val deleted: Boolean = false,
) {
    fun toDomain() = FuelTank(id, name, facility, capacityL, levelL, ratedPowerKw, loadKw, fullLoadLph, updatedAt, pendingSync)

    companion object {
        fun of(t: FuelTank, deleted: Boolean = false) = FuelTankEntity(
            t.id, t.name, t.facility, t.capacityL, t.levelL, t.ratedPowerKw, t.loadKw, t.fullLoadLph,
            t.updatedAt, t.pendingSync, deleted,
        )
    }
}

@Dao
interface FuelTankDao {
    /** Flow: Room повторює запит після кожної зміни таблиці — UI оновлюється автоматично. */
    @Query("SELECT * FROM fuel_tanks WHERE deleted = 0 ORDER BY name")
    fun observeActive(): Flow<List<FuelTankEntity>>

    @Query("SELECT * FROM fuel_tanks")
    suspend fun getAll(): List<FuelTankEntity>

    @Upsert
    suspend fun upsert(entities: List<FuelTankEntity>)

    @Query("UPDATE fuel_tanks SET deleted = 1, pending_sync = 1 WHERE id = :id")
    suspend fun markDeleted(id: String)

    @Query("DELETE FROM fuel_tanks WHERE id = :id")
    suspend fun remove(id: String)

    @Query("SELECT COUNT(*) FROM fuel_tanks")
    suspend fun count(): Int
}

@Database(entities = [FuelTankEntity::class], version = 1, exportSchema = true)
abstract class FuelReserveDatabase : RoomDatabase() {
    abstract fun tankDao(): FuelTankDao

    companion object {
        @Volatile
        private var instance: FuelReserveDatabase? = null

        fun getInstance(context: Context): FuelReserveDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, FuelReserveDatabase::class.java, "fuel_reserve.db")
                    .build()
                    .also { instance = it }
            }
    }
}

/** Запис локального сховища разом із прапорцем видалення. */
data class StoredTank(val tank: FuelTank, val deleted: Boolean)

/**
 * Локальне джерело даних. Repository залежить лише від цього інтерфейсу,
 * тому Room можна замінити (або підставити фейк у тестах) без змін у бізнес-логіці.
 */
interface LocalTankDataSource {
    fun observeTanks(): Flow<List<FuelTank>>
    suspend fun getAll(): List<StoredTank>
    suspend fun save(tanks: List<FuelTank>)
    suspend fun markDeleted(id: String)
    suspend fun remove(id: String)
    suspend fun isEmpty(): Boolean
}

/** Реалізація локального джерела на Room. */
class RoomTankDataSource(private val dao: FuelTankDao) : LocalTankDataSource {
    override fun observeTanks() = dao.observeActive().map { list -> list.map(FuelTankEntity::toDomain) }
    override suspend fun getAll() = dao.getAll().map { StoredTank(it.toDomain(), it.deleted) }
    override suspend fun save(tanks: List<FuelTank>) = dao.upsert(tanks.map { FuelTankEntity.of(it) })
    override suspend fun markDeleted(id: String) = dao.markDeleted(id)
    override suspend fun remove(id: String) = dao.remove(id)
    override suspend fun isEmpty() = dao.count() == 0
}
