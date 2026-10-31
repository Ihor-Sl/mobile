package com.example.myapplication.pw9.domain

/** Поля форми резервуара. */
enum class TankField { NAME, FACILITY, CAPACITY, LEVEL, RATED, LOAD, CONSUMPTION }

/** Значення форми — рядки, як їх ввів користувач. [id] = null для нового запису. */
data class TankDraft(
    val id: String? = null,
    val name: String = "",
    val facility: String = "",
    val capacity: String = "",
    val level: String = "",
    val rated: String = "",
    val load: String = "",
    val consumption: String = "",
) {
    companion object {
        fun of(t: FuelTank) = TankDraft(
            id = t.id, name = t.name, facility = t.facility,
            capacity = t.capacityL.plain(), level = t.levelL.plain(), rated = t.ratedPowerKw.plain(),
            load = t.loadKw.plain(), consumption = t.fullLoadLph.plain(),
        )

        private fun Double.plain() = if (this == Math.floor(this)) toLong().toString() else toString()
    }
}

/**
 * Перевірка введених значень перед збереженням у Repository.
 * Повертає помилки по полях; порожній результат означає, що запис коректний.
 */
object TankValidator {

    fun number(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

    fun validate(d: TankDraft, otherNames: Collection<String>): Map<TankField, String> {
        val errors = mutableMapOf<TankField, String>()
        val name = d.name.trim()
        when {
            name.isEmpty() -> errors[TankField.NAME] = "Вкажіть назву"
            name.length > 40 -> errors[TankField.NAME] = "Не більше 40 символів"
            otherNames.any { it.equals(name, ignoreCase = true) } -> errors[TankField.NAME] = "Така назва вже є"
        }
        if (d.facility.isBlank()) errors[TankField.FACILITY] = "Вкажіть об'єкт"

        val capacity = number(d.capacity)
        val level = number(d.level)
        val rated = number(d.rated)
        val load = number(d.load)
        val consumption = number(d.consumption)

        when {
            capacity == null -> errors[TankField.CAPACITY] = "Введіть число"
            capacity !in 50.0..50_000.0 -> errors[TankField.CAPACITY] = "Від 50 до 50 000 л"
        }
        when {
            level == null -> errors[TankField.LEVEL] = "Введіть число"
            level < 0 -> errors[TankField.LEVEL] = "Не може бути від'ємним"
            capacity != null && level > capacity -> errors[TankField.LEVEL] = "Більше за об'єм бака"
        }
        when {
            rated == null -> errors[TankField.RATED] = "Введіть число"
            rated !in 5.0..5_000.0 -> errors[TankField.RATED] = "Від 5 до 5 000 кВт"
        }
        when {
            load == null -> errors[TankField.LOAD] = "Введіть число"
            load < 0 -> errors[TankField.LOAD] = "Не може бути від'ємним"
            rated != null && load > rated -> errors[TankField.LOAD] = "Перевищує потужність ДГУ"
        }
        when {
            consumption == null -> errors[TankField.CONSUMPTION] = "Введіть число"
            consumption <= 0 -> errors[TankField.CONSUMPTION] = "Має бути більше 0"
            // Перехресна перевірка: питома витрата дизеля 0,15…0,45 л/кВт·год
            rated != null && consumption / rated !in 0.15..0.45 ->
                errors[TankField.CONSUMPTION] = "Нетипово: ${"%.0f".format(rated * 0.15)}–${"%.0f".format(rated * 0.45)} л/год"
        }
        return errors
    }

    /** Перетворення коректної чернетки на доменну модель. */
    fun toTank(d: TankDraft, id: String, now: Long) = FuelTank(
        id = id,
        name = d.name.trim(),
        facility = d.facility.trim(),
        capacityL = number(d.capacity)!!,
        levelL = number(d.level)!!,
        ratedPowerKw = number(d.rated)!!,
        loadKw = number(d.load)!!,
        fullLoadLph = number(d.consumption)!!,
        updatedAt = now,
    )
}
