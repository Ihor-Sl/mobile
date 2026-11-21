package com.example.myapplication

import com.example.myapplication.facilities.FacilityCatalog
import com.example.myapplication.facilities.FacilityQuery
import com.example.myapplication.facilities.FacilityType
import com.example.myapplication.facilities.GeoPoint
import com.example.myapplication.facilities.PowerStatus
import com.example.myapplication.facilities.Readiness
import com.example.myapplication.facilities.SortKey
import com.example.myapplication.facilities.SortRule
import com.example.myapplication.facilities.applyQuery
import com.example.myapplication.facilities.distanceKm
import com.example.myapplication.pw13.data.PowerScenario
import com.example.myapplication.pw13.data.SimulatedPowerSensors
import com.example.myapplication.pw13.domain.LinkState
import com.example.myapplication.pw13.domain.PowerAssessor
import com.example.myapplication.pw13.domain.SensorId
import com.example.myapplication.pw13.domain.SensorSample
import com.example.myapplication.pw13.domain.SupplyMode
import com.example.myapplication.pw13.viewmodel.PowerFlowViewModel
import com.example.myapplication.pw14.viewmodel.FacilityMapViewModel
import com.example.myapplication.pw15.viewmodel.FacilityRegistryViewModel
import com.example.myapplication.pw16.data.LocationSource
import com.example.myapplication.pw16.viewmodel.GpsMonitorViewModel
import com.example.myapplication.pw16.viewmodel.LocationPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Перевірка логіки ПЗ 13–16; потоки виконуються у віртуальному часі, без реальних затримок. */
@OptIn(ExperimentalCoroutinesApi::class)
class Pw4LogicTest {
    private val dispatcher = StandardTestDispatcher()
    private val catalog = FacilityCatalog.sample(now = 1_000_000_000L)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.observe(block: suspend () -> Unit) {
        backgroundScope.launch(dispatcher) { block() }
    }

    @Test fun pw13_invalidSamplesAreRejected() {
        assertFalse(PowerAssessor.isValid(SensorSample(SensorId.GRID, -1.0, 7)))
        assertFalse(PowerAssessor.isValid(SensorSample(SensorId.BATTERY, 101.0, 1)))
        assertTrue(PowerAssessor.isValid(SensorSample(SensorId.GRID, 0.0, 1)))
    }

    @Test fun pw13_modePriorityAndAutonomy() {
        assertEquals(SupplyMode.GRID, PowerAssessor.mode(220.0, 0.0, 0.0))
        assertEquals(SupplyMode.GENSET, PowerAssessor.mode(0.0, 10.0, 50.0))
        assertEquals(SupplyMode.BATTERY, PowerAssessor.mode(0.0, 0.0, 50.0))
        assertEquals(SupplyMode.BLACKOUT, PowerAssessor.mode(0.0, 0.0, 0.0))
        // 420 / (22 · (0,3 + 0,7 · 55 / 80)) + 40 · 0,8 / 55 = 24,44 + 0,58
        assertEquals(25.02, PowerAssessor.autonomyHours(420.0, 100.0, 55.0), 0.01)
    }

    @Test fun pw13_thresholdParsing() {
        assertEquals(12.5, PowerAssessor.parseThreshold("12,5")!!, 0.001)
        assertNull(PowerAssessor.parseThreshold("0"))
        assertNull(PowerAssessor.parseThreshold("abc"))
    }

    @Test fun pw13_combinesSensorsAndReactsToOutage() = runTest(dispatcher) {
        val sensors = SimulatedPowerSensors()
        val vm = PowerFlowViewModel(sensors, dispatcher)
        observe { vm.uiState.collect {} }
        advanceTimeBy(1_500); runCurrent()
        assertEquals(SupplyMode.GRID, vm.uiState.value.reading?.mode)

        vm.setScenario(PowerScenario.OUTAGE)
        advanceTimeBy(2_000); runCurrent()
        val reading = vm.uiState.value.reading!!
        assertEquals(SupplyMode.GENSET, reading.mode)
        assertTrue(reading.fuelLiters < 420.0)
        assertTrue(vm.uiState.value.events.any { "ДГУ" in it })
    }

    @Test fun pw13_transientFaultRecoversThroughRetry() = runTest(dispatcher) {
        val sensors = SimulatedPowerSensors()
        val vm = PowerFlowViewModel(sensors, dispatcher)
        observe { vm.uiState.collect {} }
        advanceTimeBy(1_500); runCurrent()

        vm.injectTransientFault(SensorId.FUEL)
        advanceTimeBy(600); runCurrent()
        assertEquals(LinkState.Retrying(1, 3), vm.uiState.value.links[SensorId.FUEL])
        advanceTimeBy(1_500); runCurrent()
        assertEquals(LinkState.Online, vm.uiState.value.links[SensorId.FUEL])
    }

    @Test fun pw13_permanentFaultFailsAndReconnectRestoresStream() = runTest(dispatcher) {
        val sensors = SimulatedPowerSensors()
        val vm = PowerFlowViewModel(sensors, dispatcher)
        observe { vm.uiState.collect {} }
        advanceTimeBy(1_500); runCurrent()

        vm.injectPermanentFault(SensorId.LOAD)
        advanceTimeBy(9_000); runCurrent()
        assertTrue(vm.uiState.value.links[SensorId.LOAD] is LinkState.Failed)
        // Інші датчики продовжують працювати, а combine використовує останнє значення
        assertEquals(LinkState.Online, vm.uiState.value.links[SensorId.GRID])

        vm.reconnect(SensorId.LOAD)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(LinkState.Online, vm.uiState.value.links[SensorId.LOAD])
    }

    @Test fun pw13_thresholdIsDebounced() = runTest(dispatcher) {
        val vm = PowerFlowViewModel(SimulatedPowerSensors(), dispatcher)
        observe { vm.uiState.collect {} }
        advanceTimeBy(1_500); runCurrent()

        vm.onThresholdChange("48")
        advanceTimeBy(200); runCurrent()
        assertEquals(12.0, vm.uiState.value.thresholdHours, 0.001)
        advanceTimeBy(1_200); runCurrent()
        assertEquals(48.0, vm.uiState.value.thresholdHours, 0.001)
        assertTrue(vm.uiState.value.belowThreshold)
    }

    @Test fun pw14_selectionIsSharedAndClearedWhenFilteredOut() = runTest(dispatcher) {
        val vm = FacilityMapViewModel(catalog)
        runCurrent()
        vm.select("F-04")
        runCurrent()
        assertEquals("F-04", vm.uiState.value.selectedId)

        vm.toggleReadiness(Readiness.READY)
        runCurrent()
        assertNull(vm.uiState.value.selectedId)
        assertTrue(vm.uiState.value.visible.all { it.readiness == Readiness.READY })
    }

    @Test fun pw15_readinessMatchesNorm() {
        val byId = catalog.associateBy { it.id }
        assertEquals(17.19, byId.getValue("F-02").burnLph, 0.01)
        assertEquals(Readiness.READY, byId.getValue("F-02").readiness)
        assertEquals(16.58, byId.getValue("F-03").autonomyHours, 0.01)
        assertEquals(Readiness.LIMITED, byId.getValue("F-03").readiness)
        assertEquals(Readiness.CRITICAL, byId.getValue("F-04").readiness)
    }

    @Test fun pw15_multiLevelFilterAndSort() {
        val query = FacilityQuery(
            types = setOf(FacilityType.HOSPITAL, FacilityType.WATER),
            statuses = setOf(PowerStatus.GRID, PowerStatus.RESERVE),
            minPowerKw = 100.0,
            primary = SortRule(SortKey.TYPE),
            secondary = SortRule(SortKey.POWER, descending = true),
        )
        assertEquals(listOf("F-07", "F-01", "F-06"), catalog.applyQuery(query).map { it.id })
    }

    @Test fun pw15_searchIgnoresCaseAndMatchesDistrict() {
        assertEquals(setOf("F-04", "F-08"), catalog.applyQuery(FacilityQuery(text = "дарниц")).map { it.id }.toSet())
        assertEquals(listOf("F-03"), catalog.applyQuery(FacilityQuery(text = "ЕНЕРГОХАБ")).map { it.id })
    }

    @Test fun pw15_selectionSummaryAndBulkStatus() = runTest(dispatcher) {
        val vm = FacilityRegistryViewModel(catalog, clock = { 2_000_000_000L })
        observe { vm.uiState.collect {} }
        runCurrent()
        vm.toggleSelected("F-02")
        vm.toggleSelected("F-03")
        runCurrent()
        assertEquals(330.0, vm.uiState.value.selectedPowerKw, 0.001)
        assertEquals(235.0, vm.uiState.value.selectedLoadKw, 0.001)

        vm.setStatusForSelected(PowerStatus.RESERVE)
        runCurrent()
        val updated = vm.uiState.value.visible.first { it.id == "F-03" }
        assertEquals(PowerStatus.RESERVE, updated.status)
        assertEquals(2_000_000_000L, updated.updatedAt)
    }

    @Test fun pw15_searchIsDebounced() = runTest(dispatcher) {
        val vm = FacilityRegistryViewModel(catalog)
        observe { vm.uiState.collect {} }
        runCurrent()
        vm.onSearchChange("котельня")
        advanceTimeBy(200); runCurrent()
        assertEquals(12, vm.uiState.value.visible.size)
        advanceTimeBy(200); runCurrent()
        assertEquals(2, vm.uiState.value.visible.size)
    }

    @Test fun pw16_haversineDistance() {
        // Київ — Львів приблизно 468 км
        assertEquals(468.0, distanceKm(GeoPoint(50.4501, 30.5234), GeoPoint(49.8397, 24.0297)), 3.0)
        assertEquals(0.0, distanceKm(GeoPoint(50.0, 30.0), GeoPoint(50.0, 30.0)), 0.0001)
    }

    @Test fun pw16_locationStartsOnlyAfterPermissionAndUpdatesOrder() = runTest(dispatcher) {
        val positions = MutableSharedFlow<GeoPoint>(replay = 1)
        var subscriptions = 0
        val source = LocationSource { flow { subscriptions++; positions.collect { emit(it) } } }
        val vm = GpsMonitorViewModel(source, catalog)
        observe { vm.uiState.collect {} }
        runCurrent()
        assertEquals(0, subscriptions)

        vm.onPermissionResult(true)
        positions.emit(GeoPoint(50.4489, 30.4574))
        runCurrent()
        assertEquals(1, subscriptions)
        assertEquals("F-03", vm.uiState.value.items.first().facility.id)
        assertEquals("F-03", vm.uiState.value.nearestReady?.facility?.id)

        // Найближчий аварійний об'єкт не пропонується як резерв
        positions.emit(GeoPoint(50.4031, 30.6161))
        runCurrent()
        assertEquals("F-04", vm.uiState.value.items.first().facility.id)
        assertEquals("F-08", vm.uiState.value.nearestReady?.facility?.id)
    }

    @Test fun pw16_deniedPermissionAndLocationErrorKeepScreenUsable() = runTest(dispatcher) {
        val vm = GpsMonitorViewModel({ flow { throw IllegalStateException("GPS off") } }, catalog)
        observe { vm.uiState.collect {} }
        vm.onPermissionResult(false)
        runCurrent()
        assertEquals(LocationPermission.DENIED, vm.uiState.value.permission)
        assertNull(vm.uiState.value.location)
        assertEquals(12, vm.uiState.value.items.size)

        vm.onPermissionResult(true)
        runCurrent()
        assertNotNull(vm.uiState.value.locationError)
        assertEquals(12, vm.uiState.value.items.size)
    }
}
