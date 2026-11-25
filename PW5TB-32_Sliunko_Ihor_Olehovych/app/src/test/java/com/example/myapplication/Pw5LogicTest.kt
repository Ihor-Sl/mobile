package com.example.myapplication

import com.example.myapplication.pw17.viewmodel.ChannelRow
import com.example.myapplication.pw17.viewmodel.PanelQuery
import com.example.myapplication.pw17.viewmodel.PanelSort
import com.example.myapplication.pw17.viewmodel.SensorPanelViewModel
import com.example.myapplication.pw17.viewmodel.applyQuery
import com.example.myapplication.pw18.data.AlertRepository
import com.example.myapplication.pw18.domain.Alert
import com.example.myapplication.pw18.domain.AlertCategory
import com.example.myapplication.pw18.domain.AlertPayload
import com.example.myapplication.pw18.domain.AlertSource
import com.example.myapplication.pw18.domain.AlertStatus
import com.example.myapplication.pw18.notify.AlertDispatcher
import com.example.myapplication.pw18.notify.AlertPoster
import com.example.myapplication.pw18.notify.AlertScheduler
import com.example.myapplication.pw18.notify.ScheduledAlert
import com.example.myapplication.pw18.viewmodel.AlertCenterViewModel
import com.example.myapplication.pw18.viewmodel.ReminderDelay
import com.example.myapplication.pw19.data.EnergyGenerator
import com.example.myapplication.pw19.domain.EnergyBucket
import com.example.myapplication.pw19.domain.EnergyHour
import com.example.myapplication.pw19.domain.EnergyMath
import com.example.myapplication.pw19.domain.TimeInterval
import com.example.myapplication.pw19.viewmodel.EnergyChartsViewModel
import com.example.myapplication.pw20.domain.MotionMath
import com.example.myapplication.pw20.domain.MotionMath.reduce
import com.example.myapplication.pw20.domain.MotionState
import com.example.myapplication.pw20.ui.components.WidgetStyle
import com.example.myapplication.pw20.viewmodel.SensorWidgetsViewModel
import com.example.myapplication.sensors.AlertLevel
import com.example.myapplication.sensors.ChannelStats
import com.example.myapplication.sensors.SensorChannel
import com.example.myapplication.sensors.SensorGroup
import com.example.myapplication.sensors.SensorRules
import com.example.myapplication.sensors.SensorSample
import com.example.myapplication.sensors.SensorSource
import com.example.myapplication.sensors.Vec3
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Перевірка логіки ПЗ 17–20; сенсори та сповіщення підмінені, час віртуальний. */
@OptIn(ExperimentalCoroutinesApi::class)
class Pw5LogicTest {
    private val dispatcher = StandardTestDispatcher()
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.observe(block: suspend () -> Unit) {
        backgroundScope.launch(dispatcher) { block() }
    }

    /** Підміна сенсорів: тест сам надсилає вимірювання та перевіряє, хто підписаний. */
    private class FakeSensors(private val unavailable: Set<SensorChannel> = emptySet()) : SensorSource {
        private val flows = SensorChannel.entries.associateWith { MutableSharedFlow<SensorSample>(extraBufferCapacity = 64) }
        override fun isAvailable(channel: SensorChannel) = channel !in unavailable
        override fun samples(channel: SensorChannel): Flow<SensorSample> = flows.getValue(channel)
        fun emit(channel: SensorChannel, value: Double, vector: Vec3? = null) {
            flows.getValue(channel).tryEmit(SensorSample(channel, value, vector))
        }
        fun active(channel: SensorChannel) = flows.getValue(channel).subscriptionCount.value > 0
    }

    private class FakePoster : AlertPoster {
        var allowed = true
        val posted = mutableListOf<Int>()
        val silent = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()
        var summary = 0
        var monitor: List<String>? = null
        override fun canPost() = allowed
        override fun post(alert: Alert, silent: Boolean) { if (silent) this.silent += alert.id else posted += alert.id }
        override fun cancel(id: Int) { cancelled += id }
        override fun updateSummary(posted: List<Alert>) { summary = posted.size }
        override fun showMonitor(lines: List<String>, activeCount: Int) { monitor = lines }
        override fun cancelMonitor() { monitor = null }
    }

    private class FakeScheduler : AlertScheduler {
        val reminders = mutableListOf<Pair<AlertPayload, Long>>()
        val snoozes = mutableListOf<Pair<AlertPayload, Long>>()
        override fun scheduleReminder(payload: AlertPayload, delayMs: Long) { reminders += payload to delayMs }
        override fun scheduleSnooze(payload: AlertPayload, delayMs: Long) { snoozes += payload to delayMs }
        override fun pending() = MutableStateFlow(emptyList<ScheduledAlert>())
        override fun cancelAll() = reminders.clear()
    }

    // ---------- ПЗ 17: сенсори ----------

    @Test fun pw17_valueConversionAndValidation() {
        // |a| = √(0² + 3² + 9,5²) = 9,9624; 9,9624 − 9,80665 = 0,1558 м/с²
        assertEquals(0.1558, SensorRules.valueOf(SensorChannel.VIBRATION, 0.0, 3.0, 9.5), 0.0001)
        assertEquals(5.0, SensorRules.valueOf(SensorChannel.ROTATION, 3.0, 4.0, 0.0), 1e-9)
        assertFalse(SensorRules.isValid(SensorChannel.LIGHT, -1.0))
        assertFalse(SensorRules.isValid(SensorChannel.BATTERY_TEMP, Double.NaN))
        assertTrue(SensorRules.isValid(SensorChannel.VIBRATION, 0.2))
    }

    @Test fun pw17_levelsForBothThresholdDirections() {
        assertEquals(AlertLevel.NORMAL, SensorRules.level(SensorChannel.VIBRATION, 1.0, 2.0))
        assertEquals(AlertLevel.WARNING, SensorRules.level(SensorChannel.VIBRATION, 1.8, 2.0))
        assertEquals(AlertLevel.ALARM, SensorRules.level(SensorChannel.VIBRATION, 2.4, 2.0))
        // Освітленість: тривога, коли значення НИЖЧЕ порогу; 50 / 40 = 125 %
        assertEquals(125.0, SensorRules.loadPercent(SensorChannel.LIGHT, 40.0, 50.0), 1e-9)
        assertEquals(AlertLevel.ALARM, SensorRules.level(SensorChannel.LIGHT, 40.0, 50.0))
        assertEquals(AlertLevel.WARNING, SensorRules.level(SensorChannel.LIGHT, 55.0, 50.0))
        assertEquals(AlertLevel.NORMAL, SensorRules.level(SensorChannel.LIGHT, 200.0, 50.0))
        assertEquals(AlertLevel.UNKNOWN, SensorRules.level(SensorChannel.LIGHT, null, 50.0))
    }

    @Test fun pw17_statsSmoothAndCountRejected() {
        val stats = ChannelStats()
            .accept(SensorChannel.VIBRATION, 1.0)
            .accept(SensorChannel.VIBRATION, Double.NaN)
            .accept(SensorChannel.VIBRATION, 2.0)
        // 0,3 · 2 + 0,7 · 1 = 1,3
        assertEquals(1.3, stats.last!!, 1e-9)
        assertEquals(1.0, stats.min!!, 1e-9)
        assertEquals(1.3, stats.max!!, 1e-9)
        assertEquals(1.15, stats.average!!, 1e-9)
        assertEquals(1, stats.rejected)
        // Датчик світла (on-change) не згладжується: інакше 180 -> 32 лк «застрягло» б на 135,6 лк
        assertEquals(32.0, ChannelStats().accept(SensorChannel.LIGHT, 180.0).accept(SensorChannel.LIGHT, 32.0).last!!, 1e-9)
    }

    @Test fun pw17_filterByTypeAndRangeThenSort() {
        val rows = listOf(
            ChannelRow(SensorChannel.LIGHT, true, ChannelStats(last = 200.0), 50.0), // 25 %
            ChannelRow(SensorChannel.VIBRATION, true, ChannelStats(last = 2.4), 2.0), // 120 %
            ChannelRow(SensorChannel.BATTERY_TEMP, true, ChannelStats(), 40.0), // немає даних
            ChannelRow(SensorChannel.MAGNETIC, true, ChannelStats(last = 70.0), 80.0), // 87,5 %
        )
        val byType = rows.applyQuery(PanelQuery(groups = setOf(SensorGroup.MOTION, SensorGroup.ENVIRONMENT)))
        assertEquals(listOf(SensorChannel.VIBRATION, SensorChannel.MAGNETIC, SensorChannel.LIGHT), byType.map { it.channel })
        val nearLimit = rows.applyQuery(PanelQuery(loadRange = 85f..PanelQuery.MAX_RANGE))
        assertEquals(listOf(SensorChannel.VIBRATION, SensorChannel.MAGNETIC), nearLimit.map { it.channel })
        val ascending = rows.applyQuery(PanelQuery(sort = PanelSort.LOAD, descending = false))
        assertEquals(SensorChannel.BATTERY_TEMP, ascending.last().channel) // без даних — у кінці
        assertEquals(SensorChannel.LIGHT, ascending.first().channel)
    }

    @Test fun pw17_viewModelSubscribesVisibleAndLogsTransitions() = runTest(dispatcher) {
        val sensors = FakeSensors(unavailable = setOf(SensorChannel.ROOM_TEMP))
        val vm = SensorPanelViewModel(sensors)
        observe { vm.uiState.collect {} }
        runCurrent()
        assertTrue(sensors.active(SensorChannel.VIBRATION))
        assertFalse(sensors.active(SensorChannel.BATTERY_CURRENT)) // не обраний — слухача немає
        vm.toggleVisible(SensorChannel.ROOM_TEMP) // недоступний сенсор не вмикається
        vm.toggleVisible(SensorChannel.VIBRATION)
        runCurrent()
        assertFalse(sensors.active(SensorChannel.VIBRATION))
        vm.toggleVisible(SensorChannel.VIBRATION)
        runCurrent()

        sensors.emit(SensorChannel.VIBRATION, 3.0)
        advanceTimeBy(300)
        runCurrent()
        val state = vm.uiState.value
        assertEquals(listOf(SensorChannel.VIBRATION), state.alarms.map { it.channel })
        assertTrue(state.events.single().contains("Тривога: Вібрація ДГУ"))
        assertFalse(SensorChannel.ROOM_TEMP in state.visible)

        vm.setThreshold(SensorChannel.VIBRATION, 5.0) // 3 / 5 = 60 % -> норма
        runCurrent()
        assertTrue(vm.uiState.value.alarms.isEmpty())
        assertTrue(vm.uiState.value.events.first().contains("повернення"))
    }

    // ---------- ПЗ 18: сповіщення ----------

    private fun dispatcherWith(poster: FakePoster, scheduler: FakeScheduler, now: () -> Long = { 1_000L }) =
        AlertDispatcher(AlertRepository(now), poster, scheduler, now)

    @Test fun pw18_alertLifecycleSyncsJournalAndShade() {
        val poster = FakePoster()
        val scheduler = FakeScheduler()
        val d = dispatcherWith(poster, scheduler)
        val grid = d.raise(AlertCategory.ALARM, "Відключення мережі", "0 В", AlertSource.OPERATOR)
        val fuel = d.raise(AlertCategory.WARNING, "Мало палива", "18 %", AlertSource.OPERATOR)
        assertEquals(listOf(100, 101), poster.posted)
        assertEquals(2, poster.summary) // дві тривоги одночасно -> підсумок групи

        d.snooze(fuel.toPayload())
        val snoozed = d.repository.book.value.find(101)!!
        assertEquals(AlertStatus.SNOOZED, snoozed.status)
        assertEquals(61_000L, snoozed.snoozedUntil)
        assertEquals(101 to 60_000L, scheduler.snoozes.single().let { it.first.id to it.second })
        assertTrue(101 in poster.cancelled)

        d.reactivate(fuel.toPayload()) // спрацював таймер WorkManager
        assertEquals(AlertStatus.ACTIVE, d.repository.book.value.find(101)!!.status)
        assertEquals(listOf(100, 101, 101), poster.posted)

        d.acknowledge(grid.id) // «Підтвердити» в шторці або на екрані
        assertEquals(AlertStatus.ACKNOWLEDGED, d.repository.book.value.find(100)!!.status)
        assertTrue(100 in poster.cancelled)
        assertEquals(listOf(101), d.repository.book.value.active.map { it.id })
    }

    @Test fun pw18_disabledCategoryAndMissingPermissionKeepJournal() {
        val poster = FakePoster()
        val d = dispatcherWith(poster, FakeScheduler())
        d.setCategoryEnabled(AlertCategory.REMINDER, false)
        val reminder = d.raise(AlertCategory.REMINDER, "Тестовий запуск ДГУ", "", AlertSource.SCHEDULE)
        assertFalse(reminder.posted)
        poster.allowed = false
        val alarm = d.raise(AlertCategory.ALARM, "Збій АВР", "", AlertSource.OPERATOR)
        assertFalse(alarm.posted)
        assertTrue(poster.posted.isEmpty())
        assertEquals(2, d.repository.book.value.active.size)
    }

    @Test fun pw18_actionAfterProcessRestartRestoresAlert() {
        val scheduler = FakeScheduler()
        val d = dispatcherWith(FakePoster(), scheduler)
        // Журнал порожній (процес перезапущено), але дія «Відкласти» несе дані тривоги в Intent
        d.snooze(AlertPayload(150, AlertCategory.ALARM, "Збій АВР", "Ручне перемикання", AlertSource.OPERATOR))
        assertEquals(AlertStatus.SNOOZED, d.repository.book.value.find(150)!!.status)
        assertEquals(151, d.raise(AlertCategory.WARNING, "Мало палива", "", AlertSource.OPERATOR).id)
        assertEquals(1, scheduler.snoozes.size)
    }

    @Test fun pw18_monitoringRaisesOnRisingEdgeAndUpdatesLive() = runTest(dispatcher) {
        val sensors = FakeSensors()
        val poster = FakePoster()
        val scheduler = FakeScheduler()
        val vm = AlertCenterViewModel(dispatcherWith(poster, scheduler), scheduler, sensors)
        observe { vm.uiState.collect {} }
        vm.setMonitoring(true)
        runCurrent()
        assertTrue(sensors.active(SensorChannel.BATTERY_TEMP))

        sensors.emit(SensorChannel.BATTERY_TEMP, 45.0)
        advanceTimeBy(1_100)
        sensors.emit(SensorChannel.BATTERY_TEMP, 46.0)
        advanceTimeBy(1_100)
        val alerts = vm.uiState.value.alerts
        assertEquals(1, alerts.size) // повторне перевищення не створює нову тривогу
        assertEquals("Тривога: Температура АКБ", alerts.single().title)
        assertEquals(AlertCategory.ALARM, alerts.single().category)

        advanceTimeBy(3_100)
        // 0,3 · 46 + 0,7 · 45 = 45,3 °C — текст сповіщення оновлено без звуку
        assertTrue(vm.uiState.value.alerts.single().text.replace(',', '.').startsWith("Зараз: 45.30 °C"))
        assertTrue(alerts.single().id in poster.silent)
        assertTrue(poster.monitor!!.single().startsWith("Температура АКБ"))

        // Подія не від сенсора (оператор, Worker) теж оновлює лічильник у шторці
        poster.monitor = null
        vm.trigger(com.example.myapplication.pw18.viewmodel.EmergencyScenario.GRID_LOST)
        advanceTimeBy(3_100)
        assertTrue(poster.monitor != null)

        vm.setMonitoring(false)
        runCurrent()
        assertNull(poster.monitor)
        assertFalse(sensors.active(SensorChannel.BATTERY_TEMP))
        assertTrue(vm.uiState.value.readings.isEmpty())
    }

    @Test fun pw18_reminderIsScheduledWithChosenDelay() {
        val scheduler = FakeScheduler()
        val vm = AlertCenterViewModel(dispatcherWith(FakePoster(), scheduler), scheduler, FakeSensors())
        vm.setReminderDelay(ReminderDelay.MIN_1)
        vm.scheduleReminder()
        val (payload, delay) = scheduler.reminders.single()
        assertEquals(60_000L, delay)
        assertEquals(AlertCategory.REMINDER, payload.category)
        assertEquals("Тестовий запуск ДГУ", payload.title)
    }

    // ---------- ПЗ 19: графіки ----------

    @Test fun pw19_bucketsCoverIntervalWithoutLosingEnergy() {
        val hours = EnergyGenerator(utc).history(1_764_000_000_000L, 720)
        TimeInterval.entries.forEach { interval ->
            val buckets = EnergyMath.buckets(hours, interval) { "" }
            assertEquals(interval.bucketCount, buckets.size)
            assertTrue(buckets.all { it.hours == interval.bucketHours })
            assertEquals(EnergyMath.window(hours, interval).sumOf { it.total }, buckets.sumOf { it.total }, 1e-6)
        }
    }

    @Test fun pw19_outageSwitchesSourcesByHour() {
        val generator = EnergyGenerator(utc)
        val day = 20_001L // 20001 mod 3 = 0 -> відключення 08:00–12:00
        val at = { hour: Int -> generator.hour(day * EnergyGenerator.DAY_MS + hour * EnergyGenerator.HOUR_MS) }
        assertEquals(at(7).total, at(7).gridKwh, 1e-9)
        assertEquals(0.2, at(8).batteryKwh / at(8).total, 1e-9) // перша година — АКБ поки стартує ДГУ
        assertEquals(at(9).total, at(9).gensetKwh, 1e-9)
        assertTrue(at(11).outage)
        assertFalse(at(12).outage)
    }

    @Test fun pw19_trendAndAdditionalIndicators() {
        assertEquals(listOf(3.0, 4.5, 6.0, 9.0), EnergyMath.movingAverage(listOf(3.0, 6.0, 9.0, 12.0)))
        val buckets = listOf(
            EnergyBucket("a", 0, 1, 100.0, 0.0, 0.0),
            EnergyBucket("b", 1, 1, 0.0, 80.0, 20.0),
            EnergyBucket("c", 2, 1, 0.0, 120.0, 0.0),
        )
        val hours = buckets.map { EnergyHour(it.startMs, 10, it.grid, it.genset, it.battery) }
        val stats = EnergyMath.stats(buckets, hours)!!
        assertEquals(320.0, stats.total, 1e-9)
        assertEquals(106.67, stats.average, 0.01)
        assertEquals(120.0, stats.peak, 1e-9)
        assertEquals(2, stats.peakIndex)
        assertEquals(62.5, stats.gensetShare, 1e-9)
        assertEquals(6.25, stats.batteryShare, 1e-9)
        assertEquals(2, stats.outageHours)
        assertEquals(54.0, stats.fuelLiters, 1e-9) // 200 кВт·год · 0,27 л
    }

    @Test fun pw19_viewModelIntervalSelectionAndLiveData() = runTest(dispatcher) {
        val start = 1_764_000_000_000L
        val vm = EnergyChartsViewModel(EnergyGenerator(utc), start, livePeriodMs = 1_000, zone = utc)
        observe { vm.uiState.collect {} }
        runCurrent()
        assertEquals(24, vm.uiState.value.buckets.size)
        vm.select(5)
        runCurrent()
        assertEquals(5, vm.uiState.value.selected)
        assertTrue(vm.describe(vm.uiState.value, 5).contains("Разом"))
        vm.setInterval(TimeInterval.MONTH)
        runCurrent()
        assertNull(vm.uiState.value.selected) // індекси іншого ряду — вибір скинуто
        assertEquals(30, vm.uiState.value.buckets.size)

        val last = vm.uiState.value.lastHourMs
        vm.setLive(true)
        advanceTimeBy(3_100)
        vm.setLive(false)
        runCurrent()
        assertEquals(last + 3 * EnergyGenerator.HOUR_MS, vm.uiState.value.lastHourMs)
    }

    // ---------- ПЗ 20: користувацькі компоненти ----------

    @Test fun pw20_tiltVibrationAndCalibration() {
        val g = SensorRules.GRAVITY
        assertEquals(0.0, MotionMath.tilt(Vec3(0.0, 0.0, g)).totalDeg, 1e-9)
        val tilted = MotionMath.tilt(Vec3(g * sin(Math.toRadians(30.0)), 0.0, g * cos(Math.toRadians(30.0))))
        assertEquals(30.0, tilted.rollDeg, 1e-9)
        assertEquals(30.0, tilted.totalDeg, 1e-9)
        assertEquals(2.0, MotionMath.vibration(Vec3(0.0, 0.0, g + 2), Vec3(0.0, 0.0, g)), 1e-9)
        assertEquals(0.0, MotionMath.tilt(Vec3(1.0, 0.0, 9.76), zero = Vec3(1.0, 0.0, 0.0)).rollDeg, 1e-9)
    }

    @Test fun pw20_scopeMappingScalesAndClips() {
        assertEquals(0.5 to 0.0, MotionMath.toScope(4.905, 0.0, 9.81, 1.0))
        assertEquals(1.0 to 0.0, MotionMath.toScope(4.905, 0.0, 9.81, 4.0)) // за межею — на краю кола
        val (x, y) = MotionMath.toScope(3.0, 4.0, 5.0, 2.0)
        assertEquals(0.6, x, 1e-9)
        assertEquals(0.8, y, 1e-9)
    }

    @Test fun pw20_reduceSeparatesGravityAndRejectsInvalid() {
        val state = MotionState()
            .reduce(SensorSample(SensorChannel.VIBRATION, 0.0, Vec3(0.0, 0.0, 9.81)))
            .reduce(SensorSample(SensorChannel.VIBRATION, 0.0, Vec3(0.0, 0.0, 19.81)))
            .reduce(SensorSample(SensorChannel.VIBRATION, 0.0, null))
            .reduce(SensorSample(SensorChannel.ROTATION, 0.0, Vec3(0.3, 0.4, 0.0)))
        // Сила тяжіння: 0,8 · 9,81 + 0,2 · 19,81 = 11,81; вібрація = 19,81 − 11,81 = 8
        assertEquals(11.81, state.gravity.z, 1e-9)
        assertEquals(8.0, state.vibration, 1e-9)
        assertEquals(0.5, state.rotation, 1e-9)
        assertEquals(1, state.rejected)
        assertEquals(3, state.samples)
    }

    @Test fun pw20_viewModelCalibratesPausesAndClampsStyle() = runTest(dispatcher) {
        val sensors = FakeSensors()
        val vm = SensorWidgetsViewModel(sensors)
        observe { vm.uiState.collect {} }
        runCurrent()
        sensors.emit(SensorChannel.VIBRATION, 0.0, Vec3(1.0, 0.0, 9.76))
        advanceTimeBy(60)
        // atan2(1; 9,76) = 5,85°
        assertEquals(5.85, vm.uiState.value.tilt.rollDeg, 0.01)
        vm.calibrate()
        runCurrent()
        assertEquals(0.0, vm.uiState.value.tilt.rollDeg, 1e-9)
        assertTrue(vm.uiState.value.calibrated)

        vm.togglePause()
        runCurrent()
        assertFalse(sensors.active(SensorChannel.VIBRATION))
        assertTrue(vm.uiState.value.paused)

        vm.onStyleChange(WidgetStyle(scale = 10f, strokeDp = 0f))
        assertEquals(4f, vm.style.value.scale)
        assertEquals(1f, vm.style.value.strokeDp)
    }
}
