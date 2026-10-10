package com.example.myapplication

import com.example.myapplication.pw7.data.TelemetryRepository
import com.example.myapplication.pw7.domain.AlarmLevel
import com.example.myapplication.pw7.domain.Telemetry
import com.example.myapplication.pw7.domain.TransferStep
import com.example.myapplication.pw7.domain.TransferTestState
import com.example.myapplication.pw7.viewmodel.MonitorViewModel
import com.example.myapplication.pw7.viewmodel.TelemetryUiState
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Тести ViewModel ПЗ 7: Main-диспетчер підмінено тестовим, тому затримки (delay) виконуються
 * у віртуальному часі, а стани StateFlow перевіряються детерміновано.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val telemetry = Telemetry(
        gridVoltage = 228.0, gridFrequencyHz = 50.1, batteryPercent = 35, batteryTempC = 24.0,
        upsLoadPercent = 60, fuelPercent = 70, generatorRunning = false,
    )

    /** Керований репозиторій: задає, чи буде збій зв'язку і чим завершаться спроби пуску ДГУ. */
    private class FakeRepository(
        var data: Telemetry,
        var failNext: Boolean = false,
        val startResults: ArrayDeque<Boolean> = ArrayDeque(),
    ) : TelemetryRepository {
        override suspend fun fetchTelemetry(): Telemetry {
            delay(100)
            if (failNext) {
                failNext = false
                throw IOException("Немає зв'язку")
            }
            return data
        }

        override suspend fun tryStartGenerator(): Boolean = startResults.removeFirstOrNull() ?: true
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadsTelemetryAndDerivesAlarms() = runTest(dispatcher) {
        val vm = MonitorViewModel(FakeRepository(telemetry))
        assertEquals(TelemetryUiState.Loading, vm.telemetry.value)
        advanceUntilIdle()
        val state = vm.telemetry.value as TelemetryUiState.Success
        assertEquals(telemetry, state.data)
        // Заряд 35 % ≤ 40 % → попередження, отримане через похідний StateFlow
        assertEquals(AlarmLevel.WARNING, vm.overallLevel.value)
        assertTrue(vm.canStartTest.value)
    }

    @Test
    fun errorKeepsLastDataAndRecoversOnRetry() = runTest(dispatcher) {
        val repo = FakeRepository(telemetry, failNext = true)
        val vm = MonitorViewModel(repo)
        advanceUntilIdle()
        val error = vm.telemetry.value as TelemetryUiState.Error
        assertNull(error.lastData)
        assertFalse(vm.canStartTest.value)

        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.telemetry.value is TelemetryUiState.Success)
    }

    @Test
    fun transferTestPassesOnSecondStartAttempt() = runTest(dispatcher) {
        val vm = MonitorViewModel(FakeRepository(telemetry, startResults = ArrayDeque(listOf(false, true))))
        advanceUntilIdle()
        vm.startTransferTest()
        advanceUntilIdle()
        val passed = vm.transferTest.value as TransferTestState.Passed
        assertEquals(2, passed.startAttempts)
        // 0,8 + 0,7 + 1,2 · 2 + 1,0 + 0,8 + 0,9 = 6,6 с
        assertEquals(6.6, passed.durationSeconds, 1e-9)
    }

    @Test
    fun transferTestFailsAfterThreeAttempts() = runTest(dispatcher) {
        val vm = MonitorViewModel(FakeRepository(telemetry, startResults = ArrayDeque(listOf(false, false, false))))
        advanceUntilIdle()
        vm.startTransferTest()
        advanceUntilIdle()
        val failed = vm.transferTest.value as TransferTestState.Failed
        assertEquals(TransferStep.GENERATOR_START, failed.step)
    }

    @Test
    fun transferTestIsBlockedDuringOutage() = runTest(dispatcher) {
        val vm = MonitorViewModel(FakeRepository(telemetry.copy(gridVoltage = 0.0)))
        advanceUntilIdle()
        vm.startTransferTest()
        advanceUntilIdle()
        assertEquals(TransferTestState.Idle, vm.transferTest.value)
    }
}
