package com.example.myapplication.pw6.data

/**
 * Календарна дата без часу. Власна реалізація замість java.time, бо java.time
 * недоступний на minSdk 24 без desugaring; до того ж так логіку легко тестувати на JVM.
 */
data class SimpleDate(val day: Int, val month: Int, val year: Int) {

    /** Кількість днів від 01.01.1970 (алгоритм для пролептичного григоріанського календаря). */
    fun toEpochDay(): Long {
        val y = if (month <= 2) year - 1L else year.toLong()
        val era = Math.floorDiv(y, 400L)
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    override fun toString(): String =
        "%02d.%02d.%04d".format(day, month, year)

    companion object {
        private val pattern = Regex("""^(\d{2})\.(\d{2})\.(\d{4})$""")

        fun isLeapYear(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

        fun daysInMonth(month: Int, year: Int): Int = when (month) {
            2 -> if (isLeapYear(year)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }

        /** Розбирає рядок «дд.мм.рррр»; повертає null, якщо формат хибний або такої дати не існує. */
        fun parse(text: String): SimpleDate? {
            val match = pattern.matchEntire(text.trim()) ?: return null
            val (d, m, y) = match.destructured.toList().map { it.toInt() }
            if (y < 1900 || m !in 1..12 || d !in 1..daysInMonth(m, y)) return null
            return SimpleDate(d, m, y)
        }
    }
}
