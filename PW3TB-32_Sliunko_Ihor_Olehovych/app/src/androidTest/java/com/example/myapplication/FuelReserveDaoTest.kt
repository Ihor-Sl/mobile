package com.example.myapplication

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.net.buildRetrofit
import com.example.myapplication.pw9.data.FuelReserveRepository
import com.example.myapplication.pw9.data.local.FuelReserveDatabase
import com.example.myapplication.pw9.data.local.FuelTankEntity
import com.example.myapplication.pw9.data.local.RoomTankDataSource
import com.example.myapplication.pw9.data.remote.MockTankServer
import com.example.myapplication.pw9.data.remote.RetrofitTankDataSource
import com.example.myapplication.pw9.data.remote.TankApi
import com.example.myapplication.pw9.domain.FuelTank
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Room у пам'яті на емуляторі: DAO і Repository з реальними Room та Retrofit. */
@RunWith(AndroidJUnit4::class)
class FuelReserveDaoTest {
    private lateinit var db: FuelReserveDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FuelReserveDatabase::class.java).build()
    }

    @After
    fun closeDb() = db.close()

    private fun tank(id: String, name: String) = FuelTank(id, name, "Об'єкт", 500.0, 300.0, 80.0, 55.0, 22.0, 1L)

    @Test
    fun upsertObserveAndTombstone() = runTest {
        val dao = db.tankDao()
        dao.upsert(listOf(FuelTankEntity.of(tank("T-2", "Б")), FuelTankEntity.of(tank("T-1", "А"))))
        assertEquals(listOf("А", "Б"), dao.observeActive().first().map { it.name }) // ORDER BY name

        dao.upsert(listOf(FuelTankEntity.of(tank("T-1", "А").copy(levelL = 100.0))))  // @Upsert оновлює
        assertEquals(100.0, dao.getAll().first { it.id == "T-1" }.levelL, 1e-9)

        dao.markDeleted("T-2")
        assertEquals(listOf("T-1"), dao.observeActive().first().map { it.id })      // видалений прихований
        assertTrue(dao.getAll().first { it.id == "T-2" }.let { it.deleted && it.pendingSync })
        dao.remove("T-2")
        assertEquals(1, dao.count())
    }

    @Test
    fun repositoryMergesServerDataIntoRoom() = runTest {
        val server = MockTankServer(latencyMs = 0)
        val repo = FuelReserveRepository(
            RoomTankDataSource(db.tankDao()),
            RetrofitTankDataSource(buildRetrofit(server).create(TankApi::class.java)),
        )
        repo.ensureSeeded()
        val report = repo.refresh().getOrThrow()
        assertEquals(2, report.updated)
        assertEquals(1, report.added)
        val tanks = repo.observeLocal().first()
        assertEquals(6, tanks.size)
        assertEquals(420.0, tanks.first { it.id == "TNK-02" }.levelL, 1e-9)
    }
}
