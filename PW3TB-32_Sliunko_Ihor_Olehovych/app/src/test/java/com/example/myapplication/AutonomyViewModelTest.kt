package com.example.myapplication

import com.example.myapplication.pw12.data.AutonomyRepository
import com.example.myapplication.pw12.data.remote.AutonomyApi
import com.example.myapplication.pw12.data.remote.AutonomyDto
import com.example.myapplication.pw12.data.remote.ForecastPointDto
import com.example.myapplication.pw12.errors.AppError
import com.example.myapplication.pw12.errors.ErrorHandler
import com.example.myapplication.pw12.errors.FileErrorLogger
import com.example.myapplication.pw12.viewmodel.AutonomyViewModel
import com.example.myapplication.pw12.viewmodel.RequestState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/** API, що повертає заздалегідь задані відповіді: HTTP-код помилки або успіх (null). */
class ScriptedApi(vararg codes: Int?) : AutonomyApi {
    private val script = ArrayDeque(codes.toList())
    var calls = 0

    override suspend fun forecast(code: String, hours: Int): AutonomyDto {
        calls++
        val error = script.removeFirstOrNull()
        if (error != null) {
            throw HttpException(Response.error<Any>(error, """{"error":"e","message":"код $error"}""".toResponseBody()))
        }
        return AutonomyDto(code, "Котельня № 7", hours, 5.63, 21.33, (0..hours).map { ForecastPointDto(it, maxOf(0.0, 120 - 21.33 * it)) })
    }
}

/**
 * Перевірка ViewModel ПЗ 12 у віртуальному часі: паузи між повторами (1 с, 2 с) не чекаються реально,
 * а проміжні стани (спроба, зворотний відлік) перевіряються детерміновано.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutonomyViewModelTest {
    private val logFile = File.createTempFile("pw12vm", ".log").apply { delete(); deleteOnExit() }
    private val logger = FileErrorLogger(logFile)
    private val network = FakeNetwork()

    private fun viewModel(api: AutonomyApi) =
        AutonomyViewModel(AutonomyRepository(api, network, ErrorHandler(logger), logger))

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun transientErrorsShowCountdownThenSuccess() = runTest {
        val api = ScriptedApi(503, 503, null)
        val vm = viewModel(api)
        vm.requestForecast()
        runCurrent()
        val waiting = vm.request.value as RequestState.Loading
        assertEquals(1, waiting.attempt)
        assertEquals(1, waiting.retryInSec)                            // перша пауза 1 с
        assertEquals(503, (waiting.lastError as AppError.Http).code)
        assertTrue(vm.notice.value!!.text.startsWith("HTTP 503"))

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, (vm.request.value as RequestState.Loading).retryInSec) // друга пауза 2 с

        advanceUntilIdle()
        val success = vm.request.value as RequestState.Success
        assertEquals(3, success.attempts)
        assertEquals(3, api.calls)
        assertEquals("Успіх з 3-ї спроби", vm.notice.value?.text)
        assertEquals(2, vm.log.value.size)
    }

    @Test
    fun finalErrorOpensDialogWhichCanBeDismissed() = runTest {
        val vm = viewModel(ScriptedApi(404))
        vm.requestForecast()
        advanceUntilIdle()
        val failed = vm.request.value as RequestState.Failed
        assertEquals(404, (failed.error as AppError.Http).code)
        assertEquals(1, failed.attempts)
        assertTrue(failed.showDialog)
        vm.dismissError()
        assertFalse((vm.request.value as RequestState.Failed).showDialog)
    }

    @Test
    fun invalidInputAndNoInternetDoNotSendRequests() = runTest {
        val api = ScriptedApi()
        val vm = viewModel(api)
        vm.onCodeChange("OBJ3")
        vm.onHoursChange("0")
        vm.requestForecast()
        assertEquals("Формат: OBJ-01", vm.input.value.codeError)
        assertEquals("Від 1 до 72 год", vm.input.value.hoursError)
        assertEquals(RequestState.Idle, vm.request.value)

        vm.onCodeChange("OBJ-04")
        vm.onHoursChange("8")
        network.online.value = false
        vm.requestForecast()
        advanceUntilIdle()
        assertEquals(AppError.NoInternet, (vm.request.value as RequestState.Failed).error)
        assertEquals(0, api.calls)
    }
}
