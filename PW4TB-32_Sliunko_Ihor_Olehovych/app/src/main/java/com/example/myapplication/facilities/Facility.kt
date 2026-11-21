package com.example.myapplication.facilities

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Тип об'єкта системи аварійного електропостачання. */
enum class FacilityType(val label: String) {
    HOSPITAL("Лікарня"),
    WATER("Водоканал"),
    DATA_CENTER("ЦОД"),
    HEATING("Теплопостачання"),
    SHELTER("Укриття"),
}

/** Поточне джерело живлення або робочий стан об'єкта. */
enum class PowerStatus(val label: String) {
    GRID("Мережа"),
    RESERVE("На резерві"),
    SERVICE("ТО"),
    FAULT("Аварія"),
}

/** Готовність резерву за нормативом автономності: від 24 год — норма, від 12 год — увага. */
enum class Readiness(val label: String) {
    READY("Норма"),
    LIMITED("Увага"),
    CRITICAL("Критично"),
}

/** Географічна точка; домен не залежить від класів Google Maps, тому логіка перевіряється модульними тестами. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/** Об'єкт із резервним джерелом (ДГУ). */
data class Facility(
    val id: String,
    val name: String,
    val type: FacilityType,
    val status: PowerStatus,
    val location: GeoPoint,
    val address: String,
    val description: String,
    val reserveKw: Double,
    val loadKw: Double,
    val fullLoadLph: Double,
    val fuelLiters: Double,
    val updatedAt: Long,
) {
    /** Витрата ДГУ за часткового навантаження: 30 % холостого ходу + 70 % пропорційно навантаженню. */
    val burnLph: Double
        get() = fullLoadLph * (0.3 + 0.7 * (loadKw / reserveKw).coerceIn(0.0, 1.0))

    /** Автономність на наявному паливі, год. */
    val autonomyHours: Double
        get() = if (burnLph > 0) fuelLiters / burnLph else 0.0

    /** Несправний об'єкт завжди критичний, навіть якщо палива достатньо. */
    val readiness: Readiness
        get() = when {
            status == PowerStatus.FAULT || autonomyHours < 12 -> Readiness.CRITICAL
            autonomyHours < 24 -> Readiness.LIMITED
            else -> Readiness.READY
        }
}

/** Відстань між двома точками за формулою гаверсинуса, км. */
fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
    val earthRadiusKm = 6371.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
    return 2 * earthRadiusKm * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** Імітаційне джерело даних: вигадані об'єкти на території Києва. */
object FacilityCatalog {
    private const val MINUTE = 60_000L

    fun sample(now: Long = System.currentTimeMillis()): List<Facility> {
        fun item(
            id: String, name: String, type: FacilityType, status: PowerStatus,
            lat: Double, lng: Double, address: String, description: String,
            reserve: Double, load: Double, lph: Double, fuel: Double, minutesAgo: Int,
        ) = Facility(
            id, name, type, status, GeoPoint(lat, lng), address, description,
            reserve, load, lph, fuel, now - minutesAgo * MINUTE,
        )
        return listOf(
            item("F-01", "Міська лікарня № 1", FacilityType.HOSPITAL, PowerStatus.GRID, 50.4395, 30.5120, "Печерський р-н", "Операційні та реанімація, I категорія надійності", 200.0, 120.0, 48.0, 1000.0, 4),
            item("F-02", "Насосна станція", FacilityType.WATER, PowerStatus.RESERVE, 50.4640, 30.4310, "Шевченківський р-н", "Подача води на правий берег, працює від ДГУ", 80.0, 55.0, 22.0, 420.0, 1),
            item("F-03", "ЦОД «Енергохаб»", FacilityType.DATA_CENTER, PowerStatus.GRID, 50.4489, 30.4600, "Солом'янський р-н", "Сервери диспетчерської служби, резерв ДГУ + АКБ", 250.0, 180.0, 60.0, 800.0, 12),
            item("F-04", "Котельня № 7", FacilityType.HEATING, PowerStatus.FAULT, 50.4030, 30.6160, "Дарницький р-н", "Несправність блока АВР, потрібен виїзд бригади", 100.0, 70.0, 27.0, 120.0, 2),
            item("F-05", "Укриття «Поділ»", FacilityType.SHELTER, PowerStatus.GRID, 50.4660, 30.5150, "Подільський р-н", "Освітлення, вентиляція та зв'язок укриття", 30.0, 12.0, 9.0, 150.0, 35),
            item("F-06", "Пологовий будинок", FacilityType.HOSPITAL, PowerStatus.RESERVE, 50.4260, 30.5400, "Голосіївський р-н", "Відділення інтенсивної терапії новонароджених", 120.0, 90.0, 30.0, 360.0, 3),
            item("F-07", "Станція водопідготовки", FacilityType.WATER, PowerStatus.GRID, 50.5000, 30.4950, "Оболонський р-н", "Очищення питної води, резерв на дві лінії", 160.0, 100.0, 40.0, 900.0, 18),
            item("F-08", "Котельня «Дарниця»", FacilityType.HEATING, PowerStatus.SERVICE, 50.4280, 30.6450, "Дарницький р-н", "Планове ТО генератора до кінця зміни", 90.0, 45.0, 24.0, 200.0, 55),
            item("F-09", "Резервний ЦОД «Оболонь»", FacilityType.DATA_CENTER, PowerStatus.GRID, 50.5120, 30.4980, "Оболонський р-н", "Геозахищена копія систем диспетчеризації", 180.0, 110.0, 46.0, 1100.0, 9),
            item("F-10", "Укриття «Виноградар»", FacilityType.SHELTER, PowerStatus.RESERVE, 50.5070, 30.3970, "Подільський р-н", "Пункт незламності, живлення від ДГУ", 25.0, 15.0, 8.0, 70.0, 6),
            item("F-11", "Дитяча лікарня", FacilityType.HOSPITAL, PowerStatus.FAULT, 50.4520, 30.3800, "Святошинський р-н", "Збій запуску ДГУ, працює лише АКБ", 150.0, 95.0, 38.0, 240.0, 1),
            item("F-12", "Насосна «Теремки»", FacilityType.WATER, PowerStatus.GRID, 50.3620, 30.4560, "Голосіївський р-н", "Підкачка води для південних районів", 70.0, 40.0, 19.0, 300.0, 27),
        )
    }
}
