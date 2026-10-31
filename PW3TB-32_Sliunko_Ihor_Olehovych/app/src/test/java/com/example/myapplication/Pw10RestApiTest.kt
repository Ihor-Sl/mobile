package com.example.myapplication

import com.example.myapplication.net.buildRetrofit
import com.example.myapplication.pw10.data.DataOrigin
import com.example.myapplication.pw10.data.GridRepository
import com.example.myapplication.pw10.data.JsonFileCache
import com.example.myapplication.pw10.data.remote.GridApi
import com.example.myapplication.pw10.data.remote.MockGridServer
import com.example.myapplication.pw10.data.remote.NetworkProfile
import com.example.myapplication.pw10.data.withRetry
import com.example.myapplication.pw10.domain.ForecastFilter
import com.example.myapplication.pw10.domain.GridMapper
import com.example.myapplication.pw10.domain.OutagePlanner
import com.example.myapplication.pw10.domain.PlanSummary
import com.example.myapplication.pw10.domain.Readiness
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class Pw10RestApiTest {
    private val clock = 1_800_000_000_000L
    private val server = MockGridServer(now = { clock }, latencyMs = 0)
    private val api = buildRetrofit(server).create(GridApi::class.java)
    private val cacheFile = File.createTempFile("grid", ".json").apply { delete(); deleteOnExit() }

    private fun repository(nowMs: Long = clock) =
        GridRepository(api, JsonFileCache(cacheFile), now = { nowMs }, io = Dispatchers.Unconfined)

    @Test
    fun pw10_gsonParsesNestedJsonAndMapperRejectsInvalidRecords() = runBlocking {
        val response = api.facilities()
        assertEquals(6, response.facilities?.size)
        assertEquals(1200.0, response.facilities!![0].sources!![0].tankL)            // вкладений масив sources
        assertEquals("Київ", response.facilities[0].address?.city)                    // вкладений об'єкт address
        val mapped = GridMapper.facilities(response)
        assertEquals(5, mapped.items.size)
        assertEquals(1, mapped.rejected)                                               // порожня назва та від'ємне навантаження
        assertEquals(3, GridMapper.schedule(api.schedule("2026-10-29")).items.size)
        assertEquals(false, GridMapper.telemetry(api.telemetry(2))?.gridOnline)
    }

    @Test
    fun pw10_plannerComputesReadinessForSchedule() = runBlocking {
        val result = repository().load().getOrThrow()
        val plan = OutagePlanner.plan(result.snapshot).associateBy { it.facility.id }

        val pump = plan.getValue(2)
        assertTrue(pump.loadShedding)                                 // ДГУ 80 кВт < 90 кВт → лише критичні 55 кВт
        assertEquals(17.1875, pump.consumptionLph, 1e-9)
        assertEquals(10.82, pump.autonomyH, 0.01)                     // 180 / 17,1875 + 40·0,6·0,8 / 55
        assertEquals(10.0, pump.requiredH, 1e-9)                      // 4 + 4 + 2 год
        assertEquals(Readiness.AT_RISK, pump.readiness)
        assertEquals(71.8, pump.fuelNeededL, 0.1)                     // (15 − 10,82) · 17,1875

        assertEquals(13.10, plan.getValue(3).autonomyH, 0.01)
        assertEquals(Readiness.AT_RISK, plan.getValue(3).readiness)
        assertEquals(Readiness.SHORTAGE, plan.getValue(4).readiness)  // 120 / 21,33 = 5,6 год < 8 год
        assertEquals(136.0, plan.getValue(4).fuelNeededL, 0.1)
        assertEquals(Readiness.SECURED, plan.getValue(1).readiness)
        assertEquals(28.70, plan.getValue(1).autonomyH, 0.01)

        val summary = PlanSummary.of(plan.values.toList())
        assertEquals(mapOf(Readiness.SECURED to 2, Readiness.AT_RISK to 2, Readiness.SHORTAGE to 1), summary.byReadiness)
        assertEquals(312.2, summary.fuelToDeliverL, 0.1)
        assertEquals(1, summary.onBackupNow)

        val atRisk = ForecastFilter(readiness = Readiness.AT_RISK).apply(plan.values.toList())
        assertEquals(listOf(2, 3), atRisk.map { it.facility.id })     // за зростанням запасу: 0,8 год і 3,1 год
    }

    @Test
    fun pw10_unstableNetworkIsHandledByRetries() = runBlocking {
        server.profile = NetworkProfile.UNSTABLE
        val result = repository().load().getOrThrow()
        assertEquals(DataOrigin.NETWORK, result.origin)
        assertEquals(7, result.retries)                               // перша спроба кожного з 7 запитів — 503
        assertEquals(5, result.snapshot.facilities.size)
    }

    @Test
    fun pw10_memoryCacheAvoidsRepeatedRequests() = runBlocking {
        val repo = repository()
        repo.load().getOrThrow()
        val requests = server.totalRequests.get()
        assertEquals(DataOrigin.MEMORY, repo.load().getOrThrow().origin)
        assertEquals(requests, server.totalRequests.get())
        assertEquals(DataOrigin.NETWORK, repo.load(force = true).getOrThrow().origin)
    }

    @Test
    fun pw10_offlineUsesFileCacheOrFailsWithoutIt() = runBlocking {
        server.profile = NetworkProfile.OFFLINE
        assertTrue(repository().load().isFailure)                     // кешу ще немає

        server.profile = NetworkProfile.STABLE
        repository().load().getOrThrow()                              // записує кеш у файл
        assertTrue(cacheFile.exists())

        server.profile = NetworkProfile.OFFLINE
        val offline = repository().load().getOrThrow()                // новий екземпляр — пам'ять порожня
        assertEquals(DataOrigin.OFFLINE, offline.origin)
        assertNotNull(offline.warning)
        assertEquals(5, offline.snapshot.facilities.size)             // знімок відновлено з JSON-файлу
    }

    @Test
    fun pw10_clientErrorsAreNotRetried() = runBlocking {
        var calls = 0
        try {
            withRetry(initialDelayMs = 0) {
                calls++
                throw HttpException(Response.error<Unit>(404, "{}".toResponseBody()))
            }
            fail()
        } catch (e: HttpException) {
            assertEquals(404, e.code())
        }
        assertEquals(1, calls)
    }
}
