package com.example.myapplication

import com.example.myapplication.pw1.data.EmergencySimulator
import com.example.myapplication.pw1.data.EmergencySnapshot
import com.example.myapplication.pw1.data.PowerSource
import com.example.myapplication.pw2.data.EmergencyPowerCalculator
import com.example.myapplication.pw2.data.InputField
import com.example.myapplication.pw2.data.ParseResult
import com.example.myapplication.pw3.data.AlertLevel
import com.example.myapplication.pw3.data.PowerMode
import com.example.myapplication.pw3.data.PowerModeAdvisor
import com.example.myapplication.pw4.data.AuthValidator
import com.example.myapplication.pw4.data.PasswordStrength
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Модульні тести бізнес-логіки всіх чотирьох практичних занять (без Android-залежностей). */
class EmergencyLogicTest {

    // ---------- ПЗ 1 ----------

    @Test
    fun pw1_sourceIsGridWhenVoltageNormal() {
        assertEquals(PowerSource.GRID, EmergencySnapshot(230, 50, 10).source)
    }

    @Test
    fun pw1_sourceSwitchesToBatteryThenGenerator() {
        assertEquals(PowerSource.BATTERY, EmergencySnapshot(0, 80, 10).source)
        assertEquals(PowerSource.GENERATOR, EmergencySnapshot(0, 25, 10).source)
    }

    @Test
    fun pw1_autonomyIsZeroAtReserveLevel() {
        assertEquals(0, EmergencySnapshot(0, 20, 10).autonomyMinutes)
        // 40 кВт·год * 50 % / 10 кВт = 2 год = 120 хв
        assertEquals(120, EmergencySnapshot(0, 70, 10).autonomyMinutes)
    }

    @Test
    fun pw1_simulatorStaysInRange() {
        val random = Random(42)
        repeat(200) {
            val s = EmergencySimulator.next(random)
            assertTrue(s.batteryPercent in 8..100)
            assertTrue(s.loadKw in 5..25)
            assertTrue(s.gridVoltage in 0..240)
        }
    }

    // ---------- ПЗ 2 ----------

    private fun validInput(overrides: Map<InputField, String> = emptyMap()) =
        (InputField.defaults() + overrides).let {
            (EmergencyPowerCalculator.parse(it) as ParseResult.Valid).input
        }

    @Test
    fun pw2_parseReportsErrorsPerField() {
        val result = EmergencyPowerCalculator.parse(
            InputField.defaults() + mapOf(InputField.LOAD to "", InputField.BATTERY_CHARGE to "150", InputField.FUEL to "abc")
        )
        assertTrue(result is ParseResult.Invalid)
        val errors = (result as ParseResult.Invalid).errors
        assertEquals(setOf(InputField.LOAD, InputField.BATTERY_CHARGE, InputField.FUEL), errors.keys)
    }

    @Test
    fun pw2_parseAcceptsCommaAsDecimalSeparator() {
        val input = validInput(mapOf(InputField.FUEL_RATE to "6,5"))
        assertEquals(6.5, input.ratedFuelRateLph, 1e-9)
    }

    @Test
    fun pw2_batteryOnlyRuntime() {
        // Без ДГУ: 40 * 0.70 * 0.92 = 25.76 кВт·год; 25.76 / 15 кВт ≈ 1.7173 год
        val result = EmergencyPowerCalculator.calculate(validInput(mapOf(InputField.GENERATOR_POWER to "0")))
        assertEquals(25.76, result.usableEnergyKwh, 1e-9)
        assertEquals(0.0, result.generatorPhaseHours, 1e-9)
        assertEquals(25.76 / 15.0, result.totalHours, 1e-9)
        assertFalse(result.generatorOverloaded)
    }

    @Test
    fun pw2_generatorAddsRuntimeWhenItCoversLoad() {
        val input = validInput()
        val result = EmergencyPowerCalculator.calculate(input)
        // Завантаження 15/20 = 75 %; витрата = 6.5 * (0.3 + 0.7 * 0.75) = 5.3625 л/год; 120 л / 5.3625
        assertEquals(5.3625, result.fuelRateLph, 1e-9)
        assertEquals(120 / 5.3625, result.generatorPhaseHours, 1e-9)
        assertEquals(25.76 / 15.0, result.batteryPhaseHours, 1e-9)
        assertEquals(result.generatorPhaseHours + result.batteryPhaseHours, result.totalHours, 1e-9)
    }

    @Test
    fun pw2_overloadedGeneratorUsesBatteryForDeficit() {
        val result = EmergencyPowerCalculator.calculate(validInput(mapOf(InputField.LOAD to "30")))
        assertTrue(result.generatorOverloaded)
        // Дефіцит 10 кВт: АКБ (25.76 кВт·год) вичерпується за 2.576 год, раніше за паливо
        assertEquals(2.576, result.generatorPhaseHours, 1e-9)
        assertEquals(0.0, result.batteryPhaseHours, 1e-9)
    }

    @Test
    fun pw2_profileStartsFullAndBatteryEndsAtReserve() {
        val result = EmergencyPowerCalculator.calculate(validInput())
        val first = result.profile.first()
        val last = result.profile.last()
        assertEquals(0.0, first.timeHours, 1e-9)
        assertEquals(90.0, first.batteryPercent, 1e-9)
        assertEquals(100.0, first.fuelPercent, 1e-9)
        assertEquals(EmergencyPowerCalculator.BATTERY_RESERVE_PERCENT, last.batteryPercent, 1e-6)
        assertEquals(result.totalHours, last.timeHours, 1e-9)
        assertTrue(result.profile.zipWithNext().all { (a, b) -> b.timeHours >= a.timeHours })
    }

    @Test
    fun pw2_formatDuration() {
        assertEquals("1 год 30 хв", EmergencyPowerCalculator.formatDuration(1.5))
        assertEquals("0 год 05 хв", EmergencyPowerCalculator.formatDuration(5 / 60.0))
    }

    // ---------- ПЗ 3 ----------

    @Test
    fun pw3_gridIsOkAtModerateLoad() {
        val report = PowerModeAdvisor.evaluate(PowerMode.GRID, 15.0)
        assertEquals(AlertLevel.OK, report.level)
        assertEquals(100.0, report.coveragePercent, 1e-9)
    }

    @Test
    fun pw3_batteryOverloadedIsCritical() {
        val report = PowerModeAdvisor.evaluate(PowerMode.BATTERY, 40.0)
        assertEquals(AlertLevel.CRITICAL, report.level)
        assertEquals(25.0, report.suppliedKw, 1e-9)
        assertEquals(62.5, report.coveragePercent, 1e-9)
    }

    @Test
    fun pw3_batteryNearLimitIsWarning() {
        assertEquals(AlertLevel.WARNING, PowerModeAdvisor.evaluate(PowerMode.BATTERY, 22.0).level)
    }

    @Test
    fun pw3_loadSheddingServesOnlyCriticalShare() {
        val report = PowerModeAdvisor.evaluate(PowerMode.LOAD_SHEDDING, 20.0)
        assertEquals(8.0, report.suppliedKw, 1e-9)
        assertEquals(40.0, report.coveragePercent, 1e-9)
        assertEquals(AlertLevel.WARNING, report.level)
    }

    // ---------- ПЗ 4 ----------

    @Test
    fun pw4_emailValidation() {
        assertNull(AuthValidator.validateEmail("dispatcher@power.ua"))
        assertNotNull(AuthValidator.validateEmail(""))
        assertNotNull(AuthValidator.validateEmail("not-an-email"))
        assertNotNull(AuthValidator.validateEmail("a@b"))
    }

    @Test
    fun pw4_fullNameNeedsTwoWords() {
        assertNull(AuthValidator.validateFullName("Коваленко Олена"))
        assertNotNull(AuthValidator.validateFullName("Коваленко"))
        assertNotNull(AuthValidator.validateFullName("   "))
    }

    @Test
    fun pw4_passwordRules() {
        assertNotNull(AuthValidator.validateNewPassword("short1"))
        assertNotNull(AuthValidator.validateNewPassword("onlyletters"))
        assertNotNull(AuthValidator.validateNewPassword("12345678"))
        assertNull(AuthValidator.validateNewPassword("Power2024"))
    }

    @Test
    fun pw4_confirmPassword() {
        assertNull(AuthValidator.validateConfirmPassword("Power2024", "Power2024"))
        assertNotNull(AuthValidator.validateConfirmPassword("Power2024", "power2024"))
        assertNotNull(AuthValidator.validateConfirmPassword("Power2024", ""))
    }

    @Test
    fun pw4_passwordStrength() {
        assertEquals(PasswordStrength.WEAK, AuthValidator.passwordStrength("abc"))
        assertEquals(PasswordStrength.MEDIUM, AuthValidator.passwordStrength("Power2024"))
        assertEquals(PasswordStrength.STRONG, AuthValidator.passwordStrength("Power2024!Grid"))
    }
}
