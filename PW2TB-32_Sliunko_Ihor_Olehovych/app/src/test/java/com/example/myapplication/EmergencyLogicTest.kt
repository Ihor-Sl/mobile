package com.example.myapplication

import com.example.myapplication.pw5.data.FacilityCatalog
import com.example.myapplication.pw5.data.ReadinessStatus
import com.example.myapplication.pw5.data.ReserveAssessor
import com.example.myapplication.pw5.navigation.Screen
import com.example.myapplication.pw6.data.SimpleDate
import com.example.myapplication.pw6.data.SourceField
import com.example.myapplication.pw6.data.SourceForm
import com.example.myapplication.pw6.data.SourceFormValidator
import com.example.myapplication.pw6.data.SourceRegistry
import com.example.myapplication.pw6.data.SourceType
import com.example.myapplication.pw7.domain.AlarmEvaluator
import com.example.myapplication.pw7.domain.AlarmLevel
import com.example.myapplication.pw7.domain.Telemetry
import com.example.myapplication.pw8.data.local.EventType
import com.example.myapplication.pw8.data.local.ReserveSourceEntity
import com.example.myapplication.pw8.data.local.SourceKind
import com.example.myapplication.pw8.domain.EventDraft
import com.example.myapplication.pw8.domain.EventField
import com.example.myapplication.pw8.domain.EventValidator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Модульні тести бізнес-логіки практичних занять 5–8 (без Android-залежностей). */
class EmergencyLogicTest {

    // ---------- ПЗ 5 ----------

    private fun facility(id: Int) = FacilityCatalog.find(id)!!

    @Test
    fun pw5_hospitalIsReadyOnFullLoad() {
        val a = ReserveAssessor.assess(facility(1), criticalOnly = false)
        // АКБ: 150 · 0,8 / 120 = 1 год; ДГУ: 1000 / (38 · (0,3 + 0,7 · 120/160)) ≈ 31,9 год
        assertEquals(1.0, a.batteryHours, 1e-9)
        assertEquals(31.898, a.generatorHours, 1e-3)
        assertEquals(ReadinessStatus.READY, a.status)
    }

    @Test
    fun pw5_pumpStationDependsOnCalculationMode() {
        val full = ReserveAssessor.assess(facility(2), criticalOnly = false)
        assertFalse(full.generatorCoversLoad) // 80 кВт < 90 кВт
        assertEquals(ReadinessStatus.NOT_READY, full.status)

        val critical = ReserveAssessor.assess(facility(2), criticalOnly = true)
        assertTrue(critical.generatorCoversLoad)
        assertEquals(18.036, critical.totalHours, 1e-3)
        assertEquals(ReadinessStatus.READY, critical.status)
    }

    @Test
    fun pw5_dataCenterNeedsMoreFuel() {
        val a = ReserveAssessor.assess(facility(4), criticalOnly = false)
        assertEquals(ReadinessStatus.LIMITED, a.status)
        assertEquals(17.104, a.totalHours, 1e-3)
        // Нестача (24 − 17,104) год × 51,6 л/год ≈ 355,8 л
        assertEquals(355.84, a.fuelToAddLiters, 1e-2)
    }

    @Test
    fun pw5_summaryCountsStatuses() {
        val full = ReserveAssessor.summarize(FacilityCatalog.facilities, criticalOnly = false)
        assertEquals(Triple(2, 2, 1), Triple(full.ready, full.limited, full.notReady))
        val critical = ReserveAssessor.summarize(FacilityCatalog.facilities, criticalOnly = true)
        assertEquals(Triple(4, 1, 0), Triple(critical.ready, critical.limited, critical.notReady))
    }

    @Test
    fun pw5_routesContainArguments() {
        assertEquals("facility_details/3?criticalOnly=true", Screen.FacilityDetails.createRoute(3, true))
        assertEquals("facility_details/{facilityId}?criticalOnly={criticalOnly}", Screen.FacilityDetails.route)
    }

    // ---------- ПЗ 6 ----------

    private val today = SimpleDate(8, 10, 2026)

    private val validForm = SourceForm(
        type = SourceType.GENERATOR, inventory = "DGU-0042", power = "100", capacity = "500",
        criticalLoad = "60", email = "engineer@power.ua", serviceDate = "15.06.2026", pin = "583920",
    )

    @Test
    fun pw6_validFormHasNoErrors() {
        assertTrue(SourceFormValidator.validate(validForm, today).isEmpty())
    }

    @Test
    fun pw6_inventoryFormatAndPrefix() {
        assertNull(SourceFormValidator.validateInventory(SourceType.GENERATOR, "DGU-0042"))
        assertNotNull(SourceFormValidator.validateInventory(SourceType.GENERATOR, "UPS-0042"))
        assertNotNull(SourceFormValidator.validateInventory(SourceType.UPS, "UPS-42"))
        assertNotNull(SourceFormValidator.validateInventory(SourceType.UPS, ""))
    }

    @Test
    fun pw6_criticalLoadIsCheckedAgainstPower() {
        assertNull(SourceFormValidator.validateCriticalLoad("80", "100"))
        assertNotNull(SourceFormValidator.validateCriticalLoad("85", "100"))
        val errors = SourceFormValidator.validate(validForm.copy(criticalLoad = "90"), today)
        assertEquals(setOf(SourceField.CRITICAL_LOAD), errors.keys)
    }

    @Test
    fun pw6_capacityRangeDependsOnType() {
        assertNotNull(SourceFormValidator.validateCapacity(SourceType.GENERATOR, "10"))
        assertNull(SourceFormValidator.validateCapacity(SourceType.UPS, "10"))
    }

    @Test
    fun pw6_serviceDateRules() {
        assertEquals(20_734, SimpleDate(8, 10, 2026).toEpochDay().toInt())
        assertNull(SimpleDate.parse("31.02.2026"))
        assertNotNull(SimpleDate.parse("29.02.2024"))
        assertNull(SimpleDate.parse("29.02.2025"))
        assertNotNull(SourceFormValidator.validateServiceDate("09.10.2026", today)) // майбутнє
        assertNotNull(SourceFormValidator.validateServiceDate("01.10.2025", today)) // 372 дні тому
        assertNull(SourceFormValidator.validateServiceDate("08.10.2025", today))    // рівно 365 днів
    }

    @Test
    fun pw6_weakPinsAreRejected() {
        listOf("123456", "111111", "654321", "12345", "12a456").forEach {
            assertNotNull(it, SourceFormValidator.validatePin(it))
        }
        assertNull(SourceFormValidator.validatePin("583920"))
    }

    @Test
    fun pw6_registryRejectsDuplicates() = runTest {
        assertTrue(SourceRegistry.isInventoryTaken("DGU-0001"))
        assertFalse(SourceRegistry.isInventoryTaken("DGU-0777"))
        SourceRegistry.register(validForm.copy(inventory = "DGU-0777"))
        assertTrue(SourceRegistry.isInventoryTaken("dgu-0777"))
    }

    // ---------- ПЗ 7 ----------

    private val normal = Telemetry(
        gridVoltage = 231.0, gridFrequencyHz = 50.0, batteryPercent = 90, batteryTempC = 25.0,
        upsLoadPercent = 50, fuelPercent = 80, generatorRunning = false,
    )

    @Test
    fun pw7_normalTelemetryHasNoAlarms() {
        val alarms = AlarmEvaluator.evaluate(normal)
        assertTrue(alarms.isEmpty())
        assertEquals(AlarmLevel.NORMAL, AlarmEvaluator.overall(alarms))
        assertTrue(AlarmEvaluator.canRunTransferTest(normal))
    }

    @Test
    fun pw7_outageWithoutGeneratorIsCritical() {
        val t = normal.copy(gridVoltage = 0.0, batteryPercent = 35, fuelPercent = 20)
        val alarms = AlarmEvaluator.evaluate(t)
        assertEquals(AlarmLevel.CRITICAL, AlarmEvaluator.overall(alarms))
        // аварія мережі, низький заряд, мало палива, ДГУ не запущено
        assertEquals(4, alarms.size)
        assertFalse(AlarmEvaluator.canRunTransferTest(t))
    }

    @Test
    fun pw7_voltageDeviationIsWarning() {
        assertEquals(AlarmLevel.WARNING, AlarmEvaluator.voltageLevel(normal.copy(gridVoltage = 255.0)))
        assertEquals(AlarmLevel.NORMAL, AlarmEvaluator.voltageLevel(normal.copy(gridVoltage = 250.0)))
    }

    @Test
    fun pw7_fuelAlarmMatchesMetricLevel() {
        val t = normal.copy(fuelPercent = 8)
        assertEquals(AlarmLevel.CRITICAL, AlarmEvaluator.fuelLevel(t))
        assertEquals(AlarmLevel.CRITICAL, AlarmEvaluator.overall(AlarmEvaluator.evaluate(t)))
        assertFalse(AlarmEvaluator.canRunTransferTest(t))
    }

    // ---------- ПЗ 8 ----------

    private val generator = ReserveSourceEntity(id = 1, name = "ДГУ-1", kind = SourceKind.GENERATOR, ratedPowerKw = 100.0)
    private val ups = ReserveSourceEntity(id = 2, name = "ДБЖ", kind = SourceKind.UPS, ratedPowerKw = 50.0)
    private val now = EventValidator.parseDateTime("08.10.2026 12:00")!!

    private val validDraft = EventDraft(
        sourceId = 1, type = EventType.OUTAGE, dateTime = "07.10.2026 22:15",
        durationMin = "90", avgLoadKw = "70", fuelUsedL = "30", note = "Аварія на ТП",
    )

    @Test
    fun pw8_validDraftConvertsToEntityAndBack() {
        assertTrue(EventValidator.validate(validDraft, generator, now).isEmpty())
        val entity = EventValidator.toEntity(validDraft)
        assertEquals(90, entity.durationMin)
        assertEquals(validDraft, EventValidator.fromEntity(entity))
    }

    @Test
    fun pw8_dateMustExistAndNotBeInFuture() {
        assertNull(EventValidator.parseDateTime("31.02.2026 10:00"))
        val future = EventValidator.validate(validDraft.copy(dateTime = "09.10.2026 10:00"), generator, now)
        assertEquals(setOf(EventField.DATE), future.keys)
    }

    @Test
    fun pw8_loadAndFuelDependOnSource() {
        // 115 кВт > 110 % від 100 кВт
        assertTrue(EventField.LOAD in EventValidator.validate(validDraft.copy(avgLoadKw = "115"), generator, now))
        // ДБЖ не споживає паливо
        assertTrue(EventField.FUEL in EventValidator.validate(validDraft.copy(sourceId = 2, avgLoadKw = "40"), ups, now))
        // ДГУ 100 кВт за 90 хв: максимум 0,4 · 100 · 1,5 = 60 л
        assertTrue(EventField.FUEL in EventValidator.validate(validDraft.copy(fuelUsedL = "61"), generator, now))
        assertTrue(EventField.SOURCE in EventValidator.validate(validDraft, null, now))
    }
}
