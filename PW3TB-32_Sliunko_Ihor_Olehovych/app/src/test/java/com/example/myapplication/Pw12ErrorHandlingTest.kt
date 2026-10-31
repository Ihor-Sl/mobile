package com.example.myapplication

import com.example.myapplication.net.buildRetrofit
import com.example.myapplication.pw12.data.AutonomyRepository
import com.example.myapplication.pw12.data.ForecastInput
import com.example.myapplication.pw12.data.NetworkMonitor
import com.example.myapplication.pw12.data.remote.AutonomyApi
import com.example.myapplication.pw12.data.remote.FaultScenario
import com.example.myapplication.pw12.data.remote.FaultyAutonomyServer
import com.example.myapplication.pw12.errors.AppError
import com.example.myapplication.pw12.errors.AppException
import com.example.myapplication.pw12.errors.ErrorHandler
import com.example.myapplication.pw12.errors.FileErrorLogger
import com.example.myapplication.pw12.errors.RetryPolicy
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeNetwork(on: Boolean = true) : NetworkMonitor {
    override val online = MutableStateFlow(on)
    override fun isOnline() = online.value
}

class Pw12ErrorHandlingTest {
    private val logFile = File.createTempFile("errors", ".log").apply { delete(); deleteOnExit() }
    private val logger = FileErrorLogger(logFile, now = { 1_800_000_000_000L })
    private val server = FaultyAutonomyServer(latencyMs = 0, timeoutMs = 0)
    private val network = FakeNetwork()

    /** Короткі паузи між спробами, щоб тест не чекав секунди в реальному часі. */
    private val repo = AutonomyRepository(
        api = buildRetrofit(server).create(AutonomyApi::class.java),
        network = network,
        handler = ErrorHandler(logger),
        logger = logger,
        retryPolicy = RetryPolicy(maxAttempts = 4, baseDelayMs = 1, maxDelayMs = 10),
    )

    private fun failure(scenario: FaultScenario, code: String = "OBJ-03"): Pair<AppError, Int> = runBlocking {
        server.scenario = scenario
        var attempts = 0
        try {
            repo.forecast(code, 12, onAttempt = { attempts = it })
            throw AssertionError("Очікувалась помилка")
        } catch (e: AppException) {
            e.error to attempts
        }
    }

    @Test
    fun pw12_normalResponseIsParsedAndValidated() = runBlocking {
        val f = repo.forecast("OBJ-03", 12)
        assertEquals("ЦОД «Енергохаб»", f.name)
        assertEquals(11.64, f.autonomyH, 0.01)   // 640 / 54,96
        assertEquals(13, f.fuelByHour.size)      // точки 0…12 год
        assertEquals(0.0, f.fuelByHour.last(), 1e-6)      // 640 − 54,96 · 12 < 0 → паливо закінчилось
        assertFalse(f.enough)
        assertTrue(logger.recent().isEmpty())
    }

    @Test
    fun pw12_httpCodesMapToDifferentMessages() {
        val (e500, a500) = failure(FaultScenario.SERVER_500)
        assertEquals(500, (e500 as AppError.Http).code)
        assertEquals("Сталася помилка на сервері, спробуйте пізніше", e500.userMessage)
        assertEquals(4, a500)                                        // 5xx повторюється до вичерпання спроб

        val (e404, a404) = failure(FaultScenario.NOT_FOUND_404)
        assertEquals(404, (e404 as AppError.Http).code)
        assertFalse(e404.retryable)
        assertEquals(1, a404)                                        // 4xx не повторюється

        val (e401, _) = failure(FaultScenario.UNAUTHORIZED_401)
        assertEquals("Сесія завершилась: потрібно увійти повторно", e401.userMessage)
        assertEquals("Термін дії токена минув", (e401 as AppError.Http).serverMessage)

        val (unknown, _) = failure(FaultScenario.NORMAL, code = "OBJ-09")
        assertEquals("Об'єкт не знайдено на сервері — перевірте код об'єкта", unknown.userMessage)

        val (parse, aParse) = failure(FaultScenario.BAD_JSON)
        assertTrue(parse is AppError.Parse)
        assertEquals(1, aParse)
    }

    @Test
    fun pw12_transientErrorsAreRetriedUntilSuccess() = runBlocking {
        for ((scenario, expected) in listOf(
            FaultScenario.TRANSIENT_503 to 3,   // 503, 503, 200
            FaultScenario.TIMEOUT_ONCE to 2,    // SocketTimeoutException, 200
            FaultScenario.RATE_LIMIT_429 to 2,  // 429 Retry-After, 200
        )) {
            server.scenario = scenario
            val errors = mutableListOf<AppError>()
            var attempts = 0
            repo.forecast("OBJ-01", 6, onAttempt = { attempts = it }, onRetry = { errors += it.error })
            assertEquals(scenario.name, expected, attempts)
            assertTrue(errors.all { it.retryable })
        }
        val log = logger.recent(10)
        assertEquals(4, log.size)                                    // 2 × 503 + таймаут + 429
        assertTrue(log.any { "HTTP 503 Service Unavailable" in it && "спроба 1/4" in it })
        assertTrue(log.any { "Timeout" in it })
        assertTrue(log.any { "HTTP 429" in it })
    }

    @Test
    fun pw12_exhaustedRetriesAreLoggedToFile() {
        failure(FaultScenario.SERVER_500)
        val lines = logFile.readLines()
        assertEquals(4, lines.size)
        assertTrue(lines[0].startsWith("20"))                         // дата й час
        assertTrue("GET /autonomy/OBJ-03?hours=12" in lines[0])
        assertTrue("NullPointerException у модулі розрахунку" in lines[0])
        assertTrue(lines.last().endsWith("спроба 4/4 | без повтору"))
        logger.clear()
        assertTrue(logger.recent().isEmpty())
    }

    @Test
    fun pw12_noInternetSkipsRequest() {
        network.online.value = false
        server.scenario = FaultScenario.NORMAL
        val (error, attempts) = failure(FaultScenario.NORMAL)
        assertEquals(AppError.NoInternet, error)
        assertEquals(0, attempts)                                     // запит не надсилався
        assertTrue(logger.recent().single().contains("NoInternet"))
    }

    @Test
    fun pw12_retryDelaysGrowExponentiallyAndHonorRetryAfter() {
        val policy = RetryPolicy()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L), (1..5).map { policy.delayFor(it, AppError.Timeout) })
        assertEquals(2_000L, policy.delayFor(1, AppError.Http(429, null, retryAfterSec = 2)))
        assertTrue(AppError.Http(503, null, null).retryable)
        assertFalse(AppError.Http(403, null, null).retryable)
    }

    @Test
    fun pw12_inputValidation() {
        assertNull(ForecastInput.codeError("obj-03"))
        assertEquals("Формат: OBJ-01", ForecastInput.codeError("OBJ3"))
        assertEquals("Вкажіть код об'єкта", ForecastInput.codeError(" "))
        assertEquals("Від 1 до 72 год", ForecastInput.hoursError("100"))
        assertEquals("Ціле число годин", ForecastInput.hoursError("2.5"))
        assertNull(ForecastInput.hoursError("24"))
    }
}
