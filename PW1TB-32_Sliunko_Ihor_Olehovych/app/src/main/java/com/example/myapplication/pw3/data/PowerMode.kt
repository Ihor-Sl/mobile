package com.example.myapplication.pw3.data

import kotlin.math.min

/** Рівень тривожності, який відображається кольором у UI. */
enum class AlertLevel { OK, WARNING, CRITICAL }

/**
 * Режим роботи системи аварійного електропостачання.
 *
 * @property capacityKw максимальна потужність джерела в цьому режимі
 * @property servedSharePercent яку частку навантаження обслуговує режим (100 — усе, менше — лише критичні споживачі)
 * @property transferSeconds час переходу на це джерело
 */
enum class PowerMode(
    val title: String,
    val sourceName: String,
    val capacityKw: Double,
    val servedSharePercent: Double,
    val transferSeconds: Double,
    val description: String,
) {
    GRID("Мережа", "Зовнішня мережа 0,4 кВ", 100.0, 100.0, 0.0,
        "Штатний режим: усі споживачі живляться від мережі, АКБ підзаряджаються."),
    BATTERY("АКБ / ДБЖ", "Акумуляторна батарея", 25.0, 100.0, 0.005,
        "Мережа зникла: ДБЖ без розриву живлення підхоплює навантаження на час запуску ДГУ."),
    GENERATOR("ДГУ", "Дизель-генератор", 30.0, 100.0, 15.0,
        "ДГУ запускається та виходить на номінальні оберти, після чого приймає навантаження."),
    LOAD_SHEDDING("Скидання навантаження", "АКБ (лише критичні споживачі)", 10.0, 40.0, 2.0,
        "Ресурсів недостатньо: вимикаються другорядні споживачі, живляться лише системи безпеки та зв'язку.");
}

/** Результат оцінки режиму для заданого навантаження. */
data class ModeReport(
    val requiredKw: Double,
    val suppliedKw: Double,
    val coveragePercent: Double,
    val utilizationPercent: Double,
    val transferSeconds: Double,
    val level: AlertLevel,
    val comment: String,
)

/** Бізнес-логіка ПЗ 3: оцінка режиму живлення залежно від навантаження. */
object PowerModeAdvisor {
    private const val WARNING_UTILIZATION = 80.0

    fun evaluate(mode: PowerMode, loadKw: Double): ModeReport {
        val requiredKw = loadKw * mode.servedSharePercent / 100.0
        val suppliedKw = min(requiredKw, mode.capacityKw)
        val utilization = requiredKw / mode.capacityKw * 100.0
        val coverage = if (loadKw > 0) suppliedKw / loadKw * 100.0 else 100.0
        val demandMet = requiredKw <= mode.capacityKw

        val level = when {
            !demandMet -> AlertLevel.CRITICAL
            mode == PowerMode.LOAD_SHEDDING || utilization > WARNING_UTILIZATION -> AlertLevel.WARNING
            else -> AlertLevel.OK
        }
        val comment = when {
            !demandMet -> "Потужності джерела недостатньо: перевантаження на " +
                "%.1f кВт. Потрібне скидання навантаження або запуск іншого джерела.".format(requiredKw - mode.capacityKw)
            mode == PowerMode.LOAD_SHEDDING -> "Працюють лише критичні споживачі (%.0f %% від навантаження).".format(mode.servedSharePercent)
            utilization > WARNING_UTILIZATION -> "Джерело завантажене понад %.0f %% — запас потужності малий.".format(WARNING_UTILIZATION)
            else -> "Режим стабільний, запас потужності достатній."
        }
        return ModeReport(requiredKw, suppliedKw, coverage, utilization, mode.transferSeconds, level, comment)
    }

    /** Текст часу переходу: для ДБЖ — «менше 10 мс», для решти — секунди. */
    fun formatTransfer(seconds: Double): String = when {
        seconds <= 0.0 -> "без переходу"
        seconds < 0.01 -> "< 10 мс"
        else -> "%.0f с".format(seconds)
    }
}
