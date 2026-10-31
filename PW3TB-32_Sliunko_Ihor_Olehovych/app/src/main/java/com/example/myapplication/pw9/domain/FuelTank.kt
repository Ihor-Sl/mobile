package com.example.myapplication.pw9.domain

/**
 * Доменна модель паливного резервуара ДГУ.
 *
 * Модель не залежить ні від Room, ні від Retrofit: Data Source-и перетворюють свої
 * сутності (Entity / DTO) у цей клас, тому ViewModel і UI не знають, звідки прийшли дані.
 */
data class FuelTank(
    val id: String,
    val name: String,
    val facility: String,
    val capacityL: Double,
    val levelL: Double,
    val ratedPowerKw: Double,
    val loadKw: Double,
    /** Витрата палива при 100 % навантаження, л/год. */
    val fullLoadLph: Double,
    val updatedAt: Long,
    /** Зміна збережена локально, але ще не відправлена на сервер. */
    val pendingSync: Boolean = false,
)

enum class ReserveStatus(val label: String) {
    NORMAL("Норма"),
    WARNING("Увага"),
    CRITICAL("Критично"),
}

/**
 * Бізнес-логіка розрахунку резерву палива.
 *
 * Витрата ДГУ лінійно залежить від завантаження: 30 % номінальної витрати на холостому ході
 * плюс пропорційна частина. Норматив автономності — [NORM_HOURS] годин роботи без мережі.
 */
object FuelCalc {
    const val NORM_HOURS = 24.0
    private const val IDLE_SHARE = 0.3

    fun loadShare(t: FuelTank) = (t.loadKw / t.ratedPowerKw).coerceIn(0.0, 1.0)

    fun consumptionLph(t: FuelTank) = t.fullLoadLph * (IDLE_SHARE + (1 - IDLE_SHARE) * loadShare(t))

    fun autonomyHours(t: FuelTank) = t.levelL / consumptionLph(t)

    fun fillPercent(t: FuelTank) = t.levelL / t.capacityL * 100

    fun status(t: FuelTank): ReserveStatus {
        val hours = autonomyHours(t)
        return when {
            hours >= NORM_HOURS -> ReserveStatus.NORMAL
            hours >= NORM_HOURS / 2 -> ReserveStatus.WARNING
            else -> ReserveStatus.CRITICAL
        }
    }

    /** Скільки палива долити до нормативу (не більше вільного об'єму бака). */
    fun fuelToNormL(t: FuelTank) =
        (NORM_HOURS * consumptionLph(t) - t.levelL).coerceIn(0.0, t.capacityL - t.levelL)

    /** Автономність після доливу до нормативу (або до повного бака, якщо бак замалий). */
    fun autonomyAfterRefuel(t: FuelTank) = (t.levelL + fuelToNormL(t)) / consumptionLph(t)
}

/** Агреговані показники по всіх резервуарах (бізнес-логіка рівня Repository / ViewModel). */
data class FleetSummary(
    val count: Int = 0,
    val totalFuelL: Double = 0.0,
    val totalCapacityL: Double = 0.0,
    val fuelToDeliverL: Double = 0.0,
    val byStatus: Map<ReserveStatus, Int> = emptyMap(),
    val weakest: FuelTank? = null,
    val pendingSync: Int = 0,
) {
    val fillPercent get() = if (totalCapacityL > 0) totalFuelL / totalCapacityL * 100 else 0.0
    val weakestAutonomyH get() = weakest?.let(FuelCalc::autonomyHours)

    companion object {
        fun of(tanks: List<FuelTank>) = FleetSummary(
            count = tanks.size,
            totalFuelL = tanks.sumOf { it.levelL },
            totalCapacityL = tanks.sumOf { it.capacityL },
            fuelToDeliverL = tanks.sumOf(FuelCalc::fuelToNormL),
            byStatus = tanks.groupingBy(FuelCalc::status).eachCount(),
            weakest = tanks.minByOrNull(FuelCalc::autonomyHours),
            pendingSync = tanks.count { it.pendingSync },
        )
    }
}

enum class TankSort(val label: String) {
    AUTONOMY_ASC("Найменша автономність"),
    FILL_ASC("Найменше заповнення"),
    FUEL_NEEDED_DESC("Найбільше долити"),
    NAME("За назвою"),
}

/** Фільтр і сортування списку — однаково працюють для локальних і серверних даних. */
data class TankFilter(
    val query: String = "",
    val status: ReserveStatus? = null,
    val sort: TankSort = TankSort.AUTONOMY_ASC,
) {
    fun apply(tanks: List<FuelTank>): List<FuelTank> {
        val q = query.trim()
        val filtered = tanks.filter { t ->
            (status == null || FuelCalc.status(t) == status) &&
                (q.isEmpty() || t.name.contains(q, ignoreCase = true) || t.facility.contains(q, ignoreCase = true))
        }
        return when (sort) {
            TankSort.AUTONOMY_ASC -> filtered.sortedBy(FuelCalc::autonomyHours)
            TankSort.FILL_ASC -> filtered.sortedBy(FuelCalc::fillPercent)
            TankSort.FUEL_NEEDED_DESC -> filtered.sortedByDescending(FuelCalc::fuelToNormL)
            TankSort.NAME -> filtered.sortedBy { it.name.lowercase() }
        }
    }
}
