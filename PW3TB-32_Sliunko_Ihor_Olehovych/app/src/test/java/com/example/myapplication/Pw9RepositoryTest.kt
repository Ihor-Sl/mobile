package com.example.myapplication

import com.example.myapplication.net.buildRetrofit
import com.example.myapplication.pw9.data.FuelReserveRepository
import com.example.myapplication.pw9.data.local.LocalTankDataSource
import com.example.myapplication.pw9.data.local.StoredTank
import com.example.myapplication.pw9.data.remote.MockTankServer
import com.example.myapplication.pw9.data.remote.RetrofitTankDataSource
import com.example.myapplication.pw9.data.remote.TankApi
import com.example.myapplication.pw9.domain.FleetSummary
import com.example.myapplication.pw9.domain.FuelCalc
import com.example.myapplication.pw9.domain.FuelTank
import com.example.myapplication.pw9.domain.ReserveStatus
import com.example.myapplication.pw9.domain.TankDraft
import com.example.myapplication.pw9.domain.TankField
import com.example.myapplication.pw9.domain.TankFilter
import com.example.myapplication.pw9.domain.TankSort
import com.example.myapplication.pw9.domain.TankValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Локальне джерело в пам'яті замість Room — Repository цього не помічає. */
class FakeLocalTanks : LocalTankDataSource {
    private val rows = linkedMapOf<String, StoredTank>()
    private val flow = MutableStateFlow<List<FuelTank>>(emptyList())

    private fun publish() {
        flow.value = rows.values.filter { !it.deleted }.map { it.tank }.sortedBy { it.name }
    }

    override fun observeTanks() = flow
    override suspend fun getAll() = rows.values.toList()
    override suspend fun save(tanks: List<FuelTank>) {
        tanks.forEach { rows[it.id] = StoredTank(it, deleted = false) }
        publish()
    }
    override suspend fun markDeleted(id: String) {
        rows[id]?.let { rows[id] = StoredTank(it.tank.copy(pendingSync = true), deleted = true) }
        publish()
    }
    override suspend fun remove(id: String) {
        rows.remove(id)
        publish()
    }
    override suspend fun isEmpty() = rows.isEmpty()
}

class Pw9RepositoryTest {
    private var clock = 1_800_000_000_000L
    private val server = MockTankServer(now = { clock }, latencyMs = 0)
    private val local = FakeLocalTanks()
    private val repo = FuelReserveRepository(
        local = local,
        remote = RetrofitTankDataSource(buildRetrofit(server).create(TankApi::class.java)),
        now = { clock },
        io = Dispatchers.Unconfined,
    )

    private fun tank(id: String, level: Double, capacity: Double, rated: Double, load: Double, lph: Double) =
        FuelTank(id, "ДГУ $id", "Об'єкт", capacity, level, rated, load, lph, 0)

    @Test
    fun pw9_consumptionAutonomyAndStatus() {
        val pump = tank("TNK-02", 300.0, 500.0, 80.0, 55.0, 22.0)
        assertEquals(17.1875, FuelCalc.consumptionLph(pump), 1e-9)   // 22 · (0,3 + 0,7 · 55/80)
        assertEquals(17.45, FuelCalc.autonomyHours(pump), 0.01)      // 300 / 17,1875
        assertEquals(ReserveStatus.WARNING, FuelCalc.status(pump))   // 12 ≤ 17,45 < 24
        assertEquals(112.5, FuelCalc.fuelToNormL(pump), 1e-9)        // 24 · 17,1875 − 300
    }

    @Test
    fun pw9_fuelToNormIsLimitedByFreeTankVolume() {
        val dataCenter = tank("TNK-03", 800.0, 1000.0, 250.0, 180.0, 60.0)
        assertEquals(48.24, FuelCalc.consumptionLph(dataCenter), 1e-9)
        // Потрібно 24 · 48,24 − 800 = 357,76 л, але в бак вміщується лише 200 л
        assertEquals(200.0, FuelCalc.fuelToNormL(dataCenter), 1e-9)
        assertEquals(20.73, FuelCalc.autonomyAfterRefuel(dataCenter), 0.01)
    }

    @Test
    fun pw9_seedSummaryAggregatesAllTanks() = runBlocking {
        repo.ensureSeeded()
        val summary = FleetSummary.of(repo.observeLocal().first())
        assertEquals(5, summary.count)
        assertEquals(2450.0, summary.totalFuelL, 1e-9)
        assertEquals(68.06, summary.fillPercent, 0.01)
        assertEquals(mapOf(ReserveStatus.NORMAL to 2, ReserveStatus.WARNING to 2, ReserveStatus.CRITICAL to 1), summary.byStatus)
        assertEquals(734.42, summary.fuelToDeliverL, 0.01)            // 112,5 + 200 + 421,92
        assertEquals("TNK-04", summary.weakest?.id)                   // котельня: 90 / 21,33 = 4,2 год
    }

    @Test
    fun pw9_aggregatedRefreshMergesNewerServerRecords() = runBlocking {
        repo.ensureSeeded()
        val report = repo.refresh().getOrThrow()
        assertEquals(2, report.updated)  // TNK-02 (долив), TNK-04 (витрата) — на сервері новіші
        assertEquals(1, report.added)    // TNK-06 — лише на сервері
        val tanks = repo.observeLocal().first().associateBy { it.id }
        assertEquals(6, tanks.size)
        assertEquals(420.0, tanks.getValue("TNK-02").levelL, 1e-9)
        assertEquals(1000.0, tanks.getValue("TNK-01").levelL, 1e-9) // локальний запис не старіший — не змінюється
        val summary = FleetSummary.of(tanks.values.toList())
        assertEquals(mapOf(ReserveStatus.NORMAL to 3, ReserveStatus.WARNING to 2, ReserveStatus.CRITICAL to 1), summary.byStatus)
        assertEquals(657.44, summary.fuelToDeliverL, 0.01)
    }

    @Test
    fun pw9_cacheSkipsNetworkUntilTtlExpires() = runBlocking {
        repo.ensureSeeded()
        repo.refresh().getOrThrow()
        val requests = server.requestCount
        assertTrue(repo.refresh().getOrThrow().fromCache)
        assertEquals(requests, server.requestCount)
        clock += 61_000
        assertFalse(repo.refresh().getOrThrow().fromCache)
        assertTrue(server.requestCount > requests)
    }

    @Test
    fun pw9_offlineSaveStaysPendingAndIsPushedLater() = runBlocking {
        repo.ensureSeeded()
        server.offline = true
        val edited = repo.observeLocal().first().first { it.id == "TNK-04" }.copy(levelL = 550.0)
        val outcome = repo.save(edited)
        assertFalse(outcome.syncedWithServer)
        assertTrue(repo.observeLocal().first().first { it.id == "TNK-04" }.pendingSync)
        assertTrue(repo.refresh(force = true).isFailure)                 // помилка мережі → Result.failure
        assertEquals(5, repo.observeLocal().first().size)                // локальні дані залишились

        server.offline = false
        val report = repo.refresh(force = true).getOrThrow()
        assertEquals(1, report.pushed)
        val synced = repo.observeLocal().first().first { it.id == "TNK-04" }
        assertEquals(550.0, synced.levelL, 1e-9)                         // локальна зміна не перезаписана сервером
        assertFalse(synced.pendingSync)
        assertEquals(550.0, repo.fetchRemote(force = true).getOrThrow().tanks.first { it.id == "TNK-04" }.levelL, 1e-9)
    }

    @Test
    fun pw9_offlineDeleteUsesTombstone() = runBlocking {
        repo.ensureSeeded()
        repo.refresh().getOrThrow()
        server.offline = true
        assertFalse(repo.delete("TNK-06"))
        assertTrue(repo.observeLocal().first().none { it.id == "TNK-06" })
        server.offline = false
        repo.refresh(force = true).getOrThrow()
        // Після синхронізації запис видалено і на сервері, і повторно він не з'являється
        assertTrue(repo.observeLocal().first().none { it.id == "TNK-06" })
        assertTrue(repo.fetchRemote(force = true).getOrThrow().tanks.none { it.id == "TNK-06" })
    }

    @Test
    fun pw9_remoteModeUsesMemoryCache() = runBlocking {
        val first = repo.fetchRemote().getOrThrow()
        assertFalse(first.fromCache)
        assertEquals(6, first.tanks.size)
        assertTrue(repo.fetchRemote().getOrThrow().fromCache)
    }

    @Test
    fun pw9_filterAndSort() {
        val tanks = listOf(
            tank("A", 1000.0, 1200.0, 160.0, 120.0, 38.0),  // 31,9 год — норма
            tank("B", 90.0, 600.0, 100.0, 70.0, 27.0),      // 4,2 год — критично
            tank("C", 300.0, 500.0, 80.0, 55.0, 22.0),      // 17,5 год — увага
        )
        assertEquals(listOf("B", "C", "A"), TankFilter(sort = TankSort.AUTONOMY_ASC).apply(tanks).map { it.id })
        assertEquals(listOf("B"), TankFilter(status = ReserveStatus.CRITICAL).apply(tanks).map { it.id })
        assertEquals(listOf("C"), TankFilter(query = "дгу c").apply(tanks).map { it.id })
        assertEquals(listOf("B", "C", "A"), TankFilter(sort = TankSort.FUEL_NEEDED_DESC).apply(tanks).map { it.id })
    }

    @Test
    fun pw9_validatorRules() {
        val ok = TankDraft(name = "ДГУ-7", facility = "Школа", capacity = "400", level = "250,5", rated = "50", load = "30", consumption = "14")
        assertTrue(TankValidator.validate(ok, listOf("ДГУ-1")).isEmpty())
        assertEquals("Така назва вже є", TankValidator.validate(ok, listOf("дгу-7"))[TankField.NAME])
        assertEquals("Більше за об'єм бака", TankValidator.validate(ok.copy(level = "500"), emptyList())[TankField.LEVEL])
        assertEquals("Перевищує потужність ДГУ", TankValidator.validate(ok.copy(load = "60"), emptyList())[TankField.LOAD])
        // 50 кВт → допустима витрата 7,5…22,5 л/год (округлено 8…23)
        assertEquals("Нетипово: 8–23 л/год", TankValidator.validate(ok.copy(consumption = "40"), emptyList())[TankField.CONSUMPTION])
        assertEquals("Введіть число", TankValidator.validate(ok.copy(capacity = "abc"), emptyList())[TankField.CAPACITY])
        assertNull(TankValidator.validate(ok, emptyList())[TankField.FACILITY])
    }
}
