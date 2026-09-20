package com.example.myapplication.pw2.data

import kotlin.math.min

/**
 * Поля форми розрахунку. Кожне поле описує назву, одиницю виміру, допустимий діапазон
 * та значення за замовчуванням — тож додавання нового параметра зводиться до нового елемента enum
 * (UI будує форму, а валідація працює автоматично).
 */
enum class InputField(
    val label: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val defaultValue: String,
) {
    LOAD("Навантаження", "кВт", 0.1, 1_000.0, "15"),
    BATTERY_CAPACITY("Ємність АКБ", "кВт·год", 0.1, 10_000.0, "40"),
    BATTERY_CHARGE("Заряд АКБ", "%", 0.0, 100.0, "90"),
    INVERTER_EFFICIENCY("ККД інвертора", "%", 50.0, 100.0, "92"),
    GENERATOR_POWER("Потужність ДГУ", "кВт", 0.0, 2_000.0, "20"),
    FUEL("Запас палива", "л", 0.0, 100_000.0, "120"),
    FUEL_RATE("Витрата ДГУ (номін.)", "л/год", 0.1, 1_000.0, "6.5");

    companion object {
        /** Початкові значення всіх полів (LinkedHashMap — Serializable, тож придатна для rememberSaveable). */
        fun defaults(): Map<InputField, String> =
            LinkedHashMap<InputField, String>().apply { InputField.entries.forEach { put(it, it.defaultValue) } }
    }
}

/** Валідні вхідні дані розрахунку. */
data class EmergencyPowerInput(
    val loadKw: Double,
    val batteryCapacityKwh: Double,
    val batteryChargePercent: Double,
    val inverterEfficiencyPercent: Double,
    val generatorPowerKw: Double,
    val fuelLiters: Double,
    val ratedFuelRateLph: Double,
)

/** Точка профілю автономної роботи: час від початку аварії та рівні ресурсів. */
data class ProfilePoint(val timeHours: Double, val batteryPercent: Double, val fuelPercent: Double)

/** Результат розрахунку автономності. */
data class EmergencyPowerResult(
    /** Корисна енергія АКБ (за вирахуванням резерву та втрат в інверторі), кВт·год. */
    val usableEnergyKwh: Double,
    /** Тривалість фази, коли працює ДГУ (за потреби разом з АКБ), год. */
    val generatorPhaseHours: Double,
    /** Тривалість фази автономної роботи лише від АКБ після вичерпання палива, год. */
    val batteryPhaseHours: Double,
    val totalHours: Double,
    /** Завантаження ДГУ, %; 0, якщо ДГУ немає. */
    val generatorLoadPercent: Double,
    val generatorOverloaded: Boolean,
    /** Фактична витрата палива при поточному навантаженні, л/год. */
    val fuelRateLph: Double,
    val profile: List<ProfilePoint>,
)

/** Результат розбору та валідації форми. */
sealed interface ParseResult {
    data class Valid(val input: EmergencyPowerInput) : ParseResult
    data class Invalid(val errors: Map<InputField, String>) : ParseResult
}

/**
 * Бізнес-логіка розрахунку: жодних залежностей від Android/Compose, тому легко тестується.
 *
 * Модель сценарію «аварія мережі, найгірший випадок»:
 *  1. ДГУ (поки є паливо) бере на себе навантаження до своєї номінальної потужності;
 *     якщо навантаження більше — різницю (дефіцит) покриває АКБ.
 *  2. Після вичерпання палива все навантаження переходить на АКБ до досягнення резерву.
 */
object EmergencyPowerCalculator {
    /** Мінімально допустимий залишок заряду АКБ (захист від глибокого розряду), %. */
    const val BATTERY_RESERVE_PERCENT = 20.0

    /** Частка витрати палива на холостому ході від номінальної. */
    private const val IDLE_FUEL_FRACTION = 0.3

    private const val PROFILE_STEPS = 24
    private const val EPS = 1e-9

    /** Розбирає рядки форми (допускає кому як десятковий роздільник) та перевіряє діапазони. */
    fun parse(raw: Map<InputField, String>): ParseResult {
        val values = HashMap<InputField, Double>()
        val errors = LinkedHashMap<InputField, String>()
        for (field in InputField.entries) {
            val text = raw[field].orEmpty().trim().replace(',', '.')
            val number = text.toDoubleOrNull()
            when {
                text.isEmpty() -> errors[field] = "Введіть значення"
                number == null || number.isNaN() || number.isInfinite() -> errors[field] = "Некоректне число"
                number < field.min || number > field.max ->
                    errors[field] = "Від ${format(field.min)} до ${format(field.max)}"
                else -> values[field] = number
            }
        }
        if (errors.isNotEmpty()) return ParseResult.Invalid(errors)
        return ParseResult.Valid(
            EmergencyPowerInput(
                loadKw = values.getValue(InputField.LOAD),
                batteryCapacityKwh = values.getValue(InputField.BATTERY_CAPACITY),
                batteryChargePercent = values.getValue(InputField.BATTERY_CHARGE),
                inverterEfficiencyPercent = values.getValue(InputField.INVERTER_EFFICIENCY),
                generatorPowerKw = values.getValue(InputField.GENERATOR_POWER),
                fuelLiters = values.getValue(InputField.FUEL),
                ratedFuelRateLph = values.getValue(InputField.FUEL_RATE),
            )
        )
    }

    fun calculate(input: EmergencyPowerInput): EmergencyPowerResult {
        val efficiency = input.inverterEfficiencyPercent / 100.0
        val usableFraction = (input.batteryChargePercent - BATTERY_RESERVE_PERCENT).coerceAtLeast(0.0) / 100.0
        val usableKwh = input.batteryCapacityKwh * usableFraction * efficiency

        val hasGenerator = input.generatorPowerKw > 0 && input.fuelLiters > 0
        val generatorSupply = if (hasGenerator) min(input.loadKw, input.generatorPowerKw) else 0.0
        val deficitKw = input.loadKw - generatorSupply

        // Витрата палива лінійно залежить від завантаження: холостий хід + пропорційна частина
        val fuelRate = if (hasGenerator) {
            input.ratedFuelRateLph *
                (IDLE_FUEL_FRACTION + (1 - IDLE_FUEL_FRACTION) * generatorSupply / input.generatorPowerKw)
        } else 0.0
        val fuelHours = if (hasGenerator) input.fuelLiters / fuelRate else 0.0

        // Фаза 1: працює ДГУ; АКБ покриває лише дефіцит (якщо він є)
        val generatorPhase = when {
            !hasGenerator -> 0.0
            deficitKw < EPS -> fuelHours
            else -> min(usableKwh / deficitKw, fuelHours)
        }
        // Фаза 2: вся потужність від АКБ із залишку енергії
        val remainingKwh = (usableKwh - deficitKw * generatorPhase).coerceAtLeast(0.0)
        val batteryPhase = remainingKwh / input.loadKw

        return EmergencyPowerResult(
            usableEnergyKwh = usableKwh,
            generatorPhaseHours = generatorPhase,
            batteryPhaseHours = batteryPhase,
            totalHours = generatorPhase + batteryPhase,
            generatorLoadPercent = if (input.generatorPowerKw > 0) input.loadKw / input.generatorPowerKw * 100 else 0.0,
            generatorOverloaded = hasGenerator && input.loadKw > input.generatorPowerKw,
            fuelRateLph = fuelRate,
            profile = buildProfile(input, efficiency, usableKwh, deficitKw, generatorPhase, fuelRate, generatorPhase + batteryPhase),
        )
    }

    /** Будує кусково-лінійний профіль заряду АКБ і рівня палива для графіка. */
    private fun buildProfile(
        input: EmergencyPowerInput,
        efficiency: Double,
        usableKwh: Double,
        deficitKw: Double,
        generatorPhase: Double,
        fuelRate: Double,
        totalHours: Double,
    ): List<ProfilePoint> {
        if (totalHours <= EPS) {
            return listOf(ProfilePoint(0.0, input.batteryChargePercent, 100.0))
        }
        // Рівномірні відліки + точка зламу (кінець фази ДГУ), щоб злам на графіку був точним
        val times = (0..PROFILE_STEPS).map { totalHours * it / PROFILE_STEPS }.toMutableList()
        if (generatorPhase > EPS && generatorPhase < totalHours) times.add(generatorPhase)
        times.sort()

        return times.map { t ->
            val onGenerator = min(t, generatorPhase)
            val onBatteryOnly = (t - generatorPhase).coerceAtLeast(0.0)
            // Енергія, віддана споживачам від АКБ до моменту t
            val deliveredKwh = (deficitKw * onGenerator + input.loadKw * onBatteryOnly).coerceAtMost(usableKwh)
            val socDrop = deliveredKwh / efficiency / input.batteryCapacityKwh * 100.0
            val fuelLeft = if (input.fuelLiters > 0) {
                (1 - fuelRate * onGenerator / input.fuelLiters).coerceIn(0.0, 1.0) * 100.0
            } else 0.0
            ProfilePoint(
                timeHours = t,
                batteryPercent = (input.batteryChargePercent - socDrop).coerceAtLeast(0.0),
                fuelPercent = fuelLeft,
            )
        }
    }

    /** Форматує години у вигляді «Х год ХХ хв». */
    fun formatDuration(hours: Double): String {
        val totalMinutes = Math.round(hours * 60).toInt()
        return "${totalMinutes / 60} год ${(totalMinutes % 60).toString().padStart(2, '0')} хв"
    }

    private fun format(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}
