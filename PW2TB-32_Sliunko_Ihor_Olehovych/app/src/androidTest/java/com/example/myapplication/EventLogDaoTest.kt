package com.example.myapplication

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.pw8.data.local.EventLogDao
import com.example.myapplication.pw8.data.local.EventLogDatabase
import com.example.myapplication.pw8.data.local.EventType
import com.example.myapplication.pw8.data.local.PowerEventEntity
import com.example.myapplication.pw8.data.local.ReserveSourceEntity
import com.example.myapplication.pw8.data.local.SourceKind
import com.example.myapplication.pw8.data.remote.RemoteEvent
import com.example.myapplication.pw8.data.remote.RemoteEventDataSource
import com.example.myapplication.pw8.data.repository.EventLogRepository
import com.example.myapplication.pw8.domain.EventFilter
import com.example.myapplication.pw8.domain.EventSort
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Інструментальні тести ПЗ 8: справжня SQLite через Room у пам'яті (in-memory),
 * тому перевіряються SQL-запити, зовнішні ключі, транзакції та агрегати.
 */
@RunWith(AndroidJUnit4::class)
class EventLogDaoTest {

    private lateinit var db: EventLogDatabase
    private lateinit var dao: EventLogDao

    private fun event(sourceId: Long, type: EventType, startedAt: Long, minutes: Int, load: Double, fuel: Double = 0.0, note: String = "") =
        PowerEventEntity(sourceId = sourceId, type = type, startedAt = startedAt, durationMin = minutes,
            avgLoadKw = load, fuelUsedL = fuel, note = note)

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EventLogDatabase::class.java).build()
        dao = db.eventLogDao()
    }

    @After
    fun closeDb() = db.close()

    @Test
    fun transactionInsertsSourceWithEventsAndRelationLoadsThem() = runTest {
        val id = dao.insertSourceWithEvents(
            ReserveSourceEntity(name = "ДГУ-1", kind = SourceKind.GENERATOR, ratedPowerKw = 100.0),
            listOf(event(0, EventType.OUTAGE, 1_000, 60, 50.0), event(0, EventType.TEST, 2_000, 30, 40.0))
        )
        val withEvents = dao.getSourceWithEvents(id)!!
        assertEquals(2, withEvents.events.size)
        assertTrue(withEvents.events.all { it.sourceId == id })
    }

    @Test
    fun filterSortAndComputedColumns() = runTest {
        val g = dao.insertSource(ReserveSourceEntity(name = "ДГУ-1", kind = SourceKind.GENERATOR, ratedPowerKw = 100.0))
        val u = dao.insertSource(ReserveSourceEntity(name = "ДБЖ", kind = SourceKind.UPS, ratedPowerKw = 50.0))
        dao.insertEvent(event(g, EventType.OUTAGE, 1_000, 120, 80.0, 40.0, "кабельна лінія"))
        dao.insertEvent(event(g, EventType.TEST, 3_000, 30, 50.0, 6.0))
        dao.insertEvent(event(u, EventType.OUTAGE, 2_000, 10, 25.0))

        val outages = dao.observeEvents(EventType.OUTAGE.name, null, "", EventSort.DATE_DESC.name).first()
        assertEquals(listOf(2_000L, 1_000L), outages.map { it.event.startedAt })

        val longest = dao.observeEvents(null, null, "", EventSort.DURATION_DESC.name).first()
        assertEquals(listOf(120, 30, 10), longest.map { it.event.durationMin })

        val byNote = dao.observeEvents(null, g, "кабельна", EventSort.DATE_DESC.name).first().single()
        assertEquals(160.0, byNote.energyKwh, 1e-9)   // 80 кВт · 2 год
        assertEquals(80.0, byNote.loadPercent, 1e-9)  // 80 / 100 кВт
    }

    @Test
    fun statsAggregateAndCascadeDelete() = runTest {
        val g = dao.insertSource(ReserveSourceEntity(name = "ДГУ-1", kind = SourceKind.GENERATOR, ratedPowerKw = 100.0))
        dao.insertSource(ReserveSourceEntity(name = "ДБЖ", kind = SourceKind.UPS, ratedPowerKw = 50.0))
        dao.insertEvent(event(g, EventType.OUTAGE, 1_000, 120, 80.0, 40.0))
        dao.insertEvent(event(g, EventType.TEST, 3_000, 30, 40.0, 6.0))

        val stats = dao.observeStats().first()
        val generator = stats.first { it.sourceId == g }
        assertEquals(2, generator.eventCount)
        assertEquals(1, generator.outageCount)
        assertEquals(2.5, generator.totalHours, 1e-9)
        assertEquals(180.0, generator.totalEnergyKwh, 1e-9) // 160 + 20
        assertEquals(46.0, generator.totalFuelL, 1e-9)
        assertEquals(60.0, generator.avgLoadPercent, 1e-9)  // сер. 60 кВт від 100
        assertEquals(0, stats.first { it.name == "ДБЖ" }.eventCount) // LEFT JOIN: джерело без подій

        dao.deleteSource(dao.findSourceByName("ДГУ-1")!!)
        assertEquals(0, dao.countEvents()) // ON DELETE CASCADE
    }

    @Test
    fun repositorySyncSkipsDuplicatesAndHandlesErrors() = runTest {
        var fail = false
        val remote = object : RemoteEventDataSource {
            override suspend fun fetchRecentEvents(): List<RemoteEvent> {
                if (fail) throw IOException("offline")
                return listOf(
                    RemoteEvent("R-1", "ДГУ-9", SourceKind.GENERATOR, 60.0, EventType.OUTAGE, 1_000, 60, 30.0, 10.0, ""),
                    RemoteEvent("R-2", "ДГУ-9", SourceKind.GENERATOR, 60.0, EventType.TEST, 2_000, 20, 25.0, 3.0, ""),
                )
            }
        }
        var clock = 0L
        val repository = EventLogRepository(db, remote, now = { clock })

        val first = repository.sync().getOrThrow()
        assertEquals(2, first.added)
        assertEquals(1, first.newSources)

        // У межах TTL повторний запит віддається з кешу, мережа не викликається
        clock = 10_000
        assertTrue(repository.sync().getOrThrow().fromCache)

        // Примусова синхронізація: ті самі remote_id пропускаються
        val second = repository.sync(force = true).getOrThrow()
        assertEquals(0, second.added)
        assertEquals(2, second.skipped)

        fail = true
        assertTrue(repository.sync(force = true).isFailure)
        assertEquals(2, dao.countEvents()) // локальні дані не втрачено

        val filtered = repository.observeEvents(EventFilter(type = EventType.TEST)).first()
        assertEquals("R-2", filtered.single().event.remoteId)
        assertNull(filtered.single().event.note.takeIf { it.isNotEmpty() })
    }
}
