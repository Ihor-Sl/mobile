package com.example.myapplication.pw5.data

/** Готовність резерву об'єкта утримувати навантаження нормативний час. */
enum class ReadinessStatus(val title: String) {
    READY("Готовий"),
    LIMITED("Обмежена готовність"),
    NOT_READY("Не готовий"),
}

/** Зміна оператора. */
enum class Shift(val title: String) {
    DAY("Денна"),
    NIGHT("Нічна"),
}

/**
 * Сесія оператора: ім'я, зміна та збережене налаштування режиму розрахунку.
 * Значення повертаються з екрана профілю в головне меню через NavController (savedStateHandle).
 */
data class OperatorSession(
    val name: String = "",
    val shift: Shift = Shift.DAY,
    /** true — розрахунок лише для критичних споживачів (режим скидання навантаження). */
    val criticalOnly: Boolean = false,
) {
    val isSignedIn: Boolean get() = name.isNotBlank()
}

/** Результат оцінки резерву об'єкта. */
data class ReserveAssessment(
    val loadKw: Double,
    val batteryHours: Double,
    val generatorHours: Double,
    val totalHours: Double,
    val requiredHours: Double,
    val hasGenerator: Boolean,
    /** Чи вистачає потужності ДГУ на навантаження. */
    val generatorCoversLoad: Boolean,
    val status: ReadinessStatus,
    /** Скільки палива потрібно долити до нормативу (0 — запасу достатньо). */
    val fuelToAddLiters: Double,
    val recommendations: List<String>,
)

/** Зведення готовності по всіх об'єктах. */
data class ReadinessSummary(val ready: Int, val limited: Int, val notReady: Int) {
    val total: Int get() = ready + limited + notReady
}

/**
 * Бізнес-логіка ПЗ 5: оцінка часу автономної роботи об'єкта від АКБ та ДГУ.
 *
 * Модель: спочатку працює ДГУ (якщо він є і його потужності достатньо), після вичерпання палива
 * навантаження переходить на АКБ. Якщо потужності ДГУ не вистачає, враховується лише АКБ.
 */
object ReserveAssessor {
    /** Допустима глибина розряду АКБ. */
    const val BATTERY_DEPTH_OF_DISCHARGE = 0.8

    /** Частка витрати палива на холостому ході від номінальної. */
    private const val IDLE_FUEL_FRACTION = 0.3

    /** Частка нормативу, з якої готовність вважається «обмеженою». */
    private const val LIMITED_THRESHOLD = 0.5

    fun assess(facility: Facility, criticalOnly: Boolean): ReserveAssessment {
        val load = if (criticalOnly) facility.criticalLoadKw else facility.totalLoadKw
        val batteryHours = facility.batteryKwh * BATTERY_DEPTH_OF_DISCHARGE / load

        val hasGenerator = facility.generatorKw > 0 && facility.fuelLiters > 0
        val coversLoad = hasGenerator && facility.generatorKw >= load
        val fuelRate = if (coversLoad) fuelRate(facility, load) else 0.0
        val generatorHours = if (coversLoad) facility.fuelLiters / fuelRate else 0.0

        val total = batteryHours + generatorHours
        val required = facility.category.requiredHours
        val status = when {
            total >= required -> ReadinessStatus.READY
            total >= required * LIMITED_THRESHOLD -> ReadinessStatus.LIMITED
            else -> ReadinessStatus.NOT_READY
        }
        // Нестачу часу можна покрити паливом лише тоді, коли ДГУ тягне навантаження
        val fuelToAdd = if (coversLoad && total < required) (required - total) * fuelRate else 0.0

        val recommendations = buildList {
            if (!hasGenerator && status != ReadinessStatus.READY) {
                add("Встановити ДГУ: лише АКБ не забезпечують нормативну автономність.")
            }
            if (hasGenerator && !coversLoad) {
                add("Потужність ДГУ (%.0f кВт) менша за навантаження (%.0f кВт): перейдіть у режим критичних споживачів або замініть ДГУ."
                    .format(facility.generatorKw, load))
            }
            if (fuelToAdd > 0) add("Поповнити запас палива щонайменше на %.0f л.".format(fuelToAdd))
            if (status == ReadinessStatus.READY) add("Резерв відповідає нормативу. Плановий тест АВР — раз на місяць.")
        }

        return ReserveAssessment(
            loadKw = load,
            batteryHours = batteryHours,
            generatorHours = generatorHours,
            totalHours = total,
            requiredHours = required,
            hasGenerator = hasGenerator,
            generatorCoversLoad = coversLoad,
            status = status,
            fuelToAddLiters = fuelToAdd,
            recommendations = recommendations,
        )
    }

    /** Кількість об'єктів у кожному стані готовності для обраного режиму розрахунку. */
    fun summarize(facilities: List<Facility>, criticalOnly: Boolean): ReadinessSummary {
        val statuses = facilities.map { assess(it, criticalOnly).status }
        return ReadinessSummary(
            ready = statuses.count { it == ReadinessStatus.READY },
            limited = statuses.count { it == ReadinessStatus.LIMITED },
            notReady = statuses.count { it == ReadinessStatus.NOT_READY },
        )
    }

    /** Витрата палива лінійно залежить від завантаження: холостий хід + пропорційна частина. */
    private fun fuelRate(facility: Facility, loadKw: Double): Double =
        facility.fuelRateLph * (IDLE_FUEL_FRACTION + (1 - IDLE_FUEL_FRACTION) * loadKw / facility.generatorKw)

    /** Години у вигляді «Х год ХХ хв». */
    fun formatHours(hours: Double): String {
        val totalMinutes = Math.round(hours * 60).toInt()
        return "${totalMinutes / 60} год ${(totalMinutes % 60).toString().padStart(2, '0')} хв"
    }
}
