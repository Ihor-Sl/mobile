package com.example.myapplication.pw8.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * База даних Room журналу подій. Схема експортується (exportSchema = true) у app/schemas —
 * це потрібно для перевірки міграцій при зміні версії.
 */
@Database(
    entities = [ReserveSourceEntity::class, PowerEventEntity::class],
    version = 1,
    exportSchema = true
)
abstract class EventLogDatabase : RoomDatabase() {
    abstract fun eventLogDao(): EventLogDao

    companion object {
        private const val DB_NAME = "emergency_event_log.db"

        @Volatile
        private var instance: EventLogDatabase? = null

        /** Єдиний екземпляр БД на весь застосунок (створення бази — дорога операція). */
        fun getInstance(context: Context): EventLogDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, EventLogDatabase::class.java, DB_NAME)
                    .build()
                    .also { instance = it }
            }
    }
}
