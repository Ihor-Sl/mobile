package com.example.myapplication.pw5.data

/**
 * Категорія надійності електропостачання споживача.
 * Від неї залежить, скільки годин резервна система має утримувати навантаження без мережі.
 */
enum class ReliabilityCategory(val title: String, val requiredHours: Double) {
    SPECIAL("I категорія, особлива група", 24.0),
    FIRST("I категорія", 12.0),
    SECOND("II категорія", 2.0),
}

/**
 * Об'єкт, підключений до системи аварійного електропостачання.
 *
 * @property totalLoadKw повне навантаження об'єкта
 * @property criticalLoadKw навантаження лише відповідальних (критичних) споживачів
 * @property generatorKw номінальна потужність ДГУ; 0 — ДГУ не встановлено
 * @property fuelRateLph витрата палива ДГУ при номінальному навантаженні
 */
data class Facility(
    val id: Int,
    val name: String,
    val address: String,
    val category: ReliabilityCategory,
    val totalLoadKw: Double,
    val criticalLoadKw: Double,
    val batteryKwh: Double,
    val generatorKw: Double,
    val fuelLiters: Double,
    val fuelRateLph: Double,
)

/** Довідник об'єктів (у ПЗ 8 подібні дані зберігаються вже в базі даних Room). */
object FacilityCatalog {
    val facilities: List<Facility> = listOf(
        Facility(1, "Міська лікарня №3", "вул. Банкова, 12", ReliabilityCategory.SPECIAL,
            totalLoadKw = 120.0, criticalLoadKw = 60.0, batteryKwh = 150.0,
            generatorKw = 160.0, fuelLiters = 1_000.0, fuelRateLph = 38.0),
        Facility(2, "Насосна станція водоканалу", "просп. Перемоги, 5", ReliabilityCategory.FIRST,
            totalLoadKw = 90.0, criticalLoadKw = 55.0, batteryKwh = 40.0,
            generatorKw = 80.0, fuelLiters = 300.0, fuelRateLph = 22.0),
        Facility(3, "Котельня мікрорайону «Східний»", "вул. Заводська, 3", ReliabilityCategory.SECOND,
            totalLoadKw = 45.0, criticalLoadKw = 30.0, batteryKwh = 80.0,
            generatorKw = 0.0, fuelLiters = 0.0, fuelRateLph = 0.0),
        Facility(4, "Центр обробки даних «Енергохаб»", "вул. Політехнічна, 41", ReliabilityCategory.SPECIAL,
            totalLoadKw = 200.0, criticalLoadKw = 180.0, batteryKwh = 400.0,
            generatorKw = 250.0, fuelLiters = 800.0, fuelRateLph = 60.0),
        Facility(5, "Пожежно-рятувальна частина №12", "вул. Січових Стрільців, 8", ReliabilityCategory.FIRST,
            totalLoadKw = 35.0, criticalLoadKw = 25.0, batteryKwh = 30.0,
            generatorKw = 40.0, fuelLiters = 200.0, fuelRateLph = 12.0),
    )

    fun find(id: Int): Facility? = facilities.firstOrNull { it.id == id }
}
