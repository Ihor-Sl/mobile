package com.example.myapplication.pw7.domain

/**
 * Model (MVVM): телеметрія системи аварійного електропостачання, отримана від контролера ДБЖ/ДГУ.
 * Клас не залежить від Android — це чисті дані предметної області.
 */
data class Telemetry(
    val gridVoltage: Double,
    val gridFrequencyHz: Double,
    val batteryPercent: Int,
    val batteryTempC: Double,
    val upsLoadPercent: Int,
    val fuelPercent: Int,
    val generatorRunning: Boolean,
)

/** Рівень тривоги. Порядок важливий: що далі в переліку, то серйозніше. */
enum class AlarmLevel(val title: String) {
    NORMAL("Норма"),
    WARNING("Попередження"),
    CRITICAL("Аварія"),
}

data class Alarm(val level: AlarmLevel, val message: String)

/**
 * Бізнес-логіка: перетворює телеметрію на перелік тривог.
 * Пороги відповідають типовим вимогам до якості електроенергії та експлуатації АКБ/ДГУ.
 */
object AlarmEvaluator {
    const val VOLTAGE_NOMINAL = 230.0
    const val VOLTAGE_OUTAGE = 190.0
    const val VOLTAGE_DEVIATION = 0.1           // ±10 % від номіналу
    const val FREQUENCY_NOMINAL = 50.0
    const val FREQUENCY_DEVIATION = 0.4         // ±0,4 Гц
    const val BATTERY_WARNING = 40
    const val BATTERY_CRITICAL = 20
    const val BATTERY_TEMP_WARNING = 35.0
    const val UPS_LOAD_WARNING = 80
    const val FUEL_WARNING = 25
    const val FUEL_MIN_FOR_TEST = 10

    fun evaluate(t: Telemetry): List<Alarm> = buildList {
        val gridOk = t.gridVoltage >= VOLTAGE_OUTAGE
        when {
            !gridOk -> add(Alarm(AlarmLevel.CRITICAL, "Аварія мережі: напруга %.0f В".format(t.gridVoltage)))
            kotlin.math.abs(t.gridVoltage - VOLTAGE_NOMINAL) > VOLTAGE_NOMINAL * VOLTAGE_DEVIATION ->
                add(Alarm(AlarmLevel.WARNING, "Напруга поза межами ±10 %%: %.0f В".format(t.gridVoltage)))
        }
        if (gridOk && kotlin.math.abs(t.gridFrequencyHz - FREQUENCY_NOMINAL) > FREQUENCY_DEVIATION) {
            add(Alarm(AlarmLevel.WARNING, "Частота мережі %.2f Гц".format(t.gridFrequencyHz)))
        }
        when {
            t.batteryPercent <= BATTERY_CRITICAL -> add(Alarm(AlarmLevel.CRITICAL, "Глибокий розряд АКБ: ${t.batteryPercent} %"))
            t.batteryPercent <= BATTERY_WARNING -> add(Alarm(AlarmLevel.WARNING, "Низький заряд АКБ: ${t.batteryPercent} %"))
        }
        if (t.batteryTempC > BATTERY_TEMP_WARNING) {
            add(Alarm(AlarmLevel.WARNING, "Перегрів АКБ: %.1f °C".format(t.batteryTempC)))
        }
        if (t.upsLoadPercent > UPS_LOAD_WARNING) {
            add(Alarm(AlarmLevel.WARNING, "ДБЖ завантажений на ${t.upsLoadPercent} %"))
        }
        when {
            t.fuelPercent < FUEL_MIN_FOR_TEST -> add(Alarm(AlarmLevel.CRITICAL, "Критично мало палива ДГУ: ${t.fuelPercent} %"))
            t.fuelPercent < FUEL_WARNING -> add(Alarm(AlarmLevel.WARNING, "Мало палива ДГУ: ${t.fuelPercent} %"))
        }
        if (!gridOk && !t.generatorRunning) {
            add(Alarm(AlarmLevel.CRITICAL, "Мережі немає, а ДГУ не запущено"))
        }
    }

    // Рівні окремих показників — для підсвічування карток у UI

    fun voltageLevel(t: Telemetry): AlarmLevel = when {
        t.gridVoltage < VOLTAGE_OUTAGE -> AlarmLevel.CRITICAL
        kotlin.math.abs(t.gridVoltage - VOLTAGE_NOMINAL) > VOLTAGE_NOMINAL * VOLTAGE_DEVIATION -> AlarmLevel.WARNING
        else -> AlarmLevel.NORMAL
    }

    fun frequencyLevel(t: Telemetry): AlarmLevel =
        if (t.gridVoltage >= VOLTAGE_OUTAGE && kotlin.math.abs(t.gridFrequencyHz - FREQUENCY_NOMINAL) > FREQUENCY_DEVIATION) {
            AlarmLevel.WARNING
        } else AlarmLevel.NORMAL

    fun batteryLevel(t: Telemetry): AlarmLevel = when {
        t.batteryPercent <= BATTERY_CRITICAL -> AlarmLevel.CRITICAL
        t.batteryPercent <= BATTERY_WARNING -> AlarmLevel.WARNING
        else -> AlarmLevel.NORMAL
    }

    fun temperatureLevel(t: Telemetry): AlarmLevel =
        if (t.batteryTempC > BATTERY_TEMP_WARNING) AlarmLevel.WARNING else AlarmLevel.NORMAL

    fun upsLoadLevel(t: Telemetry): AlarmLevel =
        if (t.upsLoadPercent > UPS_LOAD_WARNING) AlarmLevel.WARNING else AlarmLevel.NORMAL

    fun fuelLevel(t: Telemetry): AlarmLevel = when {
        t.fuelPercent < FUEL_MIN_FOR_TEST -> AlarmLevel.CRITICAL
        t.fuelPercent < FUEL_WARNING -> AlarmLevel.WARNING
        else -> AlarmLevel.NORMAL
    }

    /** Загальний стан системи — найвищий рівень серед тривог. */
    fun overall(alarms: List<Alarm>): AlarmLevel = alarms.maxOfOrNull { it.level } ?: AlarmLevel.NORMAL

    /** Тест АВР можна запускати лише за наявності мережі та мінімального запасу палива. */
    fun canRunTransferTest(t: Telemetry): Boolean =
        t.gridVoltage >= VOLTAGE_OUTAGE && t.fuelPercent >= FUEL_MIN_FOR_TEST
}
