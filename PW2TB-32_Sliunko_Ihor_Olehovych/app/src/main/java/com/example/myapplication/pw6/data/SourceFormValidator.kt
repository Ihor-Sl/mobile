package com.example.myapplication.pw6.data

import java.io.Serializable

/**
 * Тип резервного джерела. Від типу залежать префікс інвентарного номера,
 * назва та діапазон поля «ємність» — тож форма динамічно змінюється при перемиканні типу.
 */
enum class SourceType(
    val title: String,
    val prefix: String,
    val capacityLabel: String,
    val capacityUnit: String,
    val capacityMin: Double,
    val capacityMax: Double,
) {
    GENERATOR("ДГУ", "DGU", "Об'єм паливного бака", "л", 20.0, 5_000.0),
    UPS("ДБЖ", "UPS", "Ємність АКБ", "кВт·год", 1.0, 2_000.0),
}

/** Поля форми реєстрації. */
enum class SourceField { INVENTORY, POWER, CAPACITY, CRITICAL_LOAD, EMAIL, SERVICE_DATE, PIN }

/**
 * Сирі (текстові) значення форми — саме в такому вигляді їх вводить користувач.
 * Serializable — щоб форму можна було зберігати через rememberSaveable (поворот екрана).
 */
data class SourceForm(
    val type: SourceType = SourceType.GENERATOR,
    val inventory: String = "",
    val power: String = "",
    val capacity: String = "",
    val criticalLoad: String = "",
    val email: String = "",
    val serviceDate: String = "",
    val pin: String = "",
) : Serializable {
    fun value(field: SourceField): String = when (field) {
        SourceField.INVENTORY -> inventory
        SourceField.POWER -> power
        SourceField.CAPACITY -> capacity
        SourceField.CRITICAL_LOAD -> criticalLoad
        SourceField.EMAIL -> email
        SourceField.SERVICE_DATE -> serviceDate
        SourceField.PIN -> pin
    }
}

/**
 * Бізнес-логіка перевірки форми. Кожна функція повертає текст помилки або null.
 * Окрім базових перевірок (непорожність, формат, діапазон) реалізовано правила предметної області:
 *  - префікс інвентарного номера має відповідати типу джерела;
 *  - навантаження критичних споживачів не більше 80 % номінальної потужності (перехресна перевірка полів);
 *  - останнє ТО не в майбутньому і не давніше ніж 12 місяців;
 *  - PIN пульта не може бути «слабким» (однакові чи послідовні цифри).
 */
object SourceFormValidator {
    const val MAX_LOAD_SHARE = 0.8
    const val SERVICE_VALIDITY_DAYS = 365
    const val PIN_LENGTH = 6

    private val inventoryRegex = Regex("""^[A-Z]{3}-\d{4}$""")
    private val emailRegex = Regex("""^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$""")

    /** Число з поля форми; кома дозволена як десятковий роздільник. */
    fun parseNumber(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }

    fun validateInventory(type: SourceType, value: String): String? {
        val text = value.trim().uppercase()
        return when {
            text.isEmpty() -> "Введіть інвентарний номер"
            !inventoryRegex.matches(text) -> "Формат: ${type.prefix}-0000 (3 літери, дефіс, 4 цифри)"
            !text.startsWith(type.prefix + "-") -> "Для ${type.title} номер має починатися з «${type.prefix}-»"
            else -> null
        }
    }

    fun validatePower(value: String): String? = validateRange(value, 1.0, 2_000.0, "кВт")

    fun validateCapacity(type: SourceType, value: String): String? =
        validateRange(value, type.capacityMin, type.capacityMax, type.capacityUnit)

    /** Перехресна перевірка: допустиме навантаження залежить від введеної потужності. */
    fun validateCriticalLoad(value: String, power: String): String? {
        validateRange(value, 0.1, 2_000.0, "кВт")?.let { return it }
        val load = parseNumber(value)!!
        val powerKw = parseNumber(power) ?: return null // поки потужність некоректна, порівнювати нема з чим
        val limit = powerKw * MAX_LOAD_SHARE
        return if (load > limit) "Не більше 80 % потужності: ≤ ${format(limit)} кВт" else null
    }

    fun validateEmail(value: String): String? = when {
        value.isBlank() -> "Введіть e-mail відповідального інженера"
        !emailRegex.matches(value.trim()) -> "Некоректний формат e-mail"
        else -> null
    }

    fun validateServiceDate(value: String, today: SimpleDate): String? {
        if (value.isBlank()) return "Введіть дату останнього ТО"
        val date = SimpleDate.parse(value) ?: return "Формат дд.мм.рррр, дата має існувати"
        val age = today.toEpochDay() - date.toEpochDay()
        return when {
            age < 0 -> "Дата не може бути в майбутньому"
            age > SERVICE_VALIDITY_DAYS -> "ТО прострочене: минуло понад 12 місяців"
            else -> null
        }
    }

    fun validatePin(value: String): String? {
        if (value.length != PIN_LENGTH || value.any { !it.isDigit() }) return "PIN — рівно $PIN_LENGTH цифр"
        val digits = value.map { it - '0' }
        val allSame = digits.distinct().size == 1
        val ascending = digits.zipWithNext().all { (a, b) -> b == a + 1 }
        val descending = digits.zipWithNext().all { (a, b) -> b == a - 1 }
        return if (allSame || ascending || descending) "Надто простий PIN (однакові або послідовні цифри)" else null
    }

    /** Перевірка всієї форми: порожня мапа означає, що форма коректна. */
    fun validate(form: SourceForm, today: SimpleDate): Map<SourceField, String> = buildMap {
        validateInventory(form.type, form.inventory)?.let { put(SourceField.INVENTORY, it) }
        validatePower(form.power)?.let { put(SourceField.POWER, it) }
        validateCapacity(form.type, form.capacity)?.let { put(SourceField.CAPACITY, it) }
        validateCriticalLoad(form.criticalLoad, form.power)?.let { put(SourceField.CRITICAL_LOAD, it) }
        validateEmail(form.email)?.let { put(SourceField.EMAIL, it) }
        validateServiceDate(form.serviceDate, today)?.let { put(SourceField.SERVICE_DATE, it) }
        validatePin(form.pin)?.let { put(SourceField.PIN, it) }
    }

    /** Підказка під полем (helper text), коли помилки немає. */
    fun hint(field: SourceField, form: SourceForm): String = when (field) {
        SourceField.INVENTORY -> "Наприклад: ${form.type.prefix}-0042"
        SourceField.POWER -> "Номінальна потужність, 1–2000 кВт"
        SourceField.CAPACITY -> "${format(form.type.capacityMin)}–${format(form.type.capacityMax)} ${form.type.capacityUnit}"
        SourceField.CRITICAL_LOAD -> parseNumber(form.power)
            ?.let { "Максимум ${format(it * MAX_LOAD_SHARE)} кВт (80 % потужності)" }
            ?: "Не більше 80 % потужності джерела"
        SourceField.EMAIL -> "Формат: engineer@power.ua"
        SourceField.SERVICE_DATE -> "дд.мм.рррр, не давніше 12 місяців"
        SourceField.PIN -> "$PIN_LENGTH цифр, не 111111 і не 123456"
    }

    /**
     * Орієнтовна автономність при навантаженні критичних споживачів, год.
     * ДБЖ: корисна енергія АКБ (глибина розряду 80 %) / навантаження.
     * ДГУ: об'єм бака / (питома витрата 0,3 л/кВт·год × навантаження).
     */
    fun estimateAutonomyHours(type: SourceType, capacity: Double, loadKw: Double): Double = when (type) {
        SourceType.UPS -> capacity * 0.8 / loadKw
        SourceType.GENERATOR -> capacity / (SPECIFIC_FUEL_RATE * loadKw)
    }

    private const val SPECIFIC_FUEL_RATE = 0.3

    private fun validateRange(value: String, min: Double, max: Double, unit: String): String? {
        if (value.isBlank()) return "Поле не може бути порожнім"
        val number = parseNumber(value) ?: return "Введіть число"
        return if (number < min || number > max) "Від ${format(min)} до ${format(max)} $unit" else null
    }

    fun format(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else "%.1f".format(value)
}
