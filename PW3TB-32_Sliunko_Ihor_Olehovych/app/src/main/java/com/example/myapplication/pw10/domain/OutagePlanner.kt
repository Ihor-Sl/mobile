package com.example.myapplication.pw10.domain

import com.example.myapplication.pw10.data.remote.FacilitiesResponse
import com.example.myapplication.pw10.data.remote.ScheduleResponse
import com.example.myapplication.pw10.data.remote.TelemetryDto

// ---------- Доменні моделі (перевірені, non-null) ----------

data class Generator(val ratedKw: Double, val tankL: Double, val fullLoadLph: Double)

data class Ups(val ratedKw: Double, val batteryKwh: Double)

data class Facility(
    val id: Int,
    val name: String,
    val category: Int,
    val address: String,
    val totalLoadKw: Double,
    val criticalLoadKw: Double,
    val generator: Generator?,
    val ups: Ups?,
)

data class Telemetry(val facilityId: Int, val measuredAt: Long, val fuelL: Double, val batterySoc: Double?, val gridOnline: Boolean)

data class OutageWindow(val fromH: Int, val toH: Int) {
    val hours get() = toH - fromH
}

data class OutageQueue(val queue: String, val facilityIds: List<Int>, val windows: List<OutageWindow>)

/** Повний набір даних з трьох ресурсів API — саме він кешується в пам'яті та у файлі. */
data class GridSnapshot(
    val fetchedAt: Long,
    val date: String,
    val facilities: List<Facility>,
    val telemetry: List<Telemetry>,
    val queues: List<OutageQueue>,
    /** Скільки записів відповіді відкинуто валідацією. */
    val rejected: Int,
)

/**
 * Перевірка отриманих даних і перетворення DTO → доменні моделі.
 * Некоректні записи відкидаються й рахуються, а не «валять» увесь екран.
 */
object GridMapper {

    data class Mapped<T>(val items: List<T>, val rejected: Int)

    fun facilities(response: FacilitiesResponse): Mapped<Facility> {
        val raw = response.facilities.orEmpty()
        val items = raw.mapNotNull { dto ->
            val id = dto.id ?: return@mapNotNull null
            val name = dto.name?.trim().takeUnless { it.isNullOrEmpty() } ?: return@mapNotNull null
            val total = dto.load?.totalKw?.takeIf { it > 0 } ?: return@mapNotNull null
            val critical = (dto.load?.criticalKw ?: total).coerceIn(0.0, total)
            val sources = dto.sources.orEmpty()
            val generator = sources.firstOrNull { it.type == "DGU" }?.let { s ->
                val rated = s.ratedKw?.takeIf { it > 0 } ?: return@let null
                val tank = s.tankL?.takeIf { it > 0 } ?: return@let null
                val lph = s.fullLoadLph?.takeIf { it > 0 } ?: return@let null
                Generator(rated, tank, lph)
            }
            val ups = sources.firstOrNull { it.type == "UPS" }?.let { s ->
                val rated = s.ratedKw?.takeIf { it > 0 } ?: return@let null
                val kwh = s.batteryKwh?.takeIf { it > 0 } ?: return@let null
                Ups(rated, kwh)
            }
            if (generator == null && ups == null) return@mapNotNull null
            val address = listOfNotNull(dto.address?.city, dto.address?.street).joinToString(", ")
            Facility(id, name, dto.category?.coerceIn(1, 3) ?: 3, address, total, critical, generator, ups)
        }
        return Mapped(items, raw.size - items.size)
    }

    fun telemetry(dto: TelemetryDto): Telemetry? {
        val id = dto.facilityId ?: return null
        val fuel = dto.fuelL?.takeIf { it >= 0 } ?: return null
        return Telemetry(id, dto.measuredAt ?: 0L, fuel, dto.batterySoc?.coerceIn(0.0, 1.0), dto.grid?.online ?: true)
    }

    fun schedule(response: ScheduleResponse): Mapped<OutageQueue> {
        val raw = response.queues.orEmpty()
        val items = raw.mapNotNull { q ->
            val name = q.queue ?: return@mapNotNull null
            val windows = q.windows.orEmpty().mapNotNull { w ->
                val from = w.fromH ?: return@mapNotNull null
                val to = w.toH ?: return@mapNotNull null
                if (from in 0..23 && to in (from + 1)..24) OutageWindow(from, to) else null
            }
            OutageQueue(name, q.facilityIds.orEmpty(), windows.sortedBy { it.fromH })
        }
        return Mapped(items, raw.size - items.size)
    }
}

// ---------- Бізнес-логіка: чи вистачить резерву на графік відключень ----------

enum class Readiness(val label: String) {
    SECURED("Забезпечено"),
    AT_RISK("На межі"),
    SHORTAGE("Не вистачить"),
}

data class FacilityForecast(
    val facility: Facility,
    val telemetry: Telemetry?,
    val queue: String?,
    val windows: List<OutageWindow>,
    /** Навантаження, яке живиться від резерву (при недостатній потужності ДГУ — лише критичне). */
    val servedLoadKw: Double,
    val loadShedding: Boolean,
    val consumptionLph: Double,
    val autonomyH: Double,
    val requiredH: Double,
    val readiness: Readiness,
    val fuelNeededL: Double,
) {
    val longestWindowH get() = windows.maxOfOrNull { it.hours } ?: 0
    val marginH get() = autonomyH - requiredH
}

object OutagePlanner {
    /** Запас: автономність має перевищувати сумарну тривалість відключень у 1,5 раза. */
    const val SAFETY_FACTOR = 1.5
    private const val IDLE_SHARE = 0.3
    private const val BATTERY_DOD = 0.8

    fun forecast(f: Facility, t: Telemetry?, queue: OutageQueue?): FacilityForecast {
        val gen = f.generator
        // Якщо ДГУ не тягне все навантаження — відключаються некритичні споживачі
        val shedding = gen != null && gen.ratedKw < f.totalLoadKw
        val load = if (shedding) f.criticalLoadKw else f.totalLoadKw
        val genUsable = gen != null && gen.ratedKw >= load
        val consumption = if (genUsable) gen!!.fullLoadLph * (IDLE_SHARE + (1 - IDLE_SHARE) * load / gen.ratedKw) else 0.0
        val fuel = t?.fuelL ?: 0.0
        val dguHours = if (genUsable && consumption > 0) fuel / consumption else 0.0
        val upsHours = f.ups?.let { it.batteryKwh * (t?.batterySoc ?: 1.0) * BATTERY_DOD / load } ?: 0.0
        val autonomy = dguHours + upsHours

        val windows = queue?.windows.orEmpty()
        val required = windows.sumOf { it.hours }.toDouble()
        val readiness = when {
            autonomy >= required * SAFETY_FACTOR -> Readiness.SECURED
            autonomy >= required -> Readiness.AT_RISK
            else -> Readiness.SHORTAGE
        }
        val freeTank = if (genUsable) gen!!.tankL - fuel else 0.0
        val needed = ((required * SAFETY_FACTOR - autonomy) * consumption).coerceIn(0.0, maxOf(freeTank, 0.0))
        return FacilityForecast(f, t, queue?.queue, windows, load, shedding, consumption, autonomy, required, readiness, needed)
    }

    fun plan(snapshot: GridSnapshot): List<FacilityForecast> {
        val telemetry = snapshot.telemetry.associateBy { it.facilityId }
        return snapshot.facilities.map { f ->
            forecast(f, telemetry[f.id], snapshot.queues.firstOrNull { f.id in it.facilityIds })
        }
    }
}

/** Агреговані показники по всіх об'єктах. */
data class PlanSummary(
    val byReadiness: Map<Readiness, Int> = emptyMap(),
    val fuelToDeliverL: Double = 0.0,
    val totalOutageH: Int = 0,
    val onBackupNow: Int = 0,
) {
    companion object {
        fun of(list: List<FacilityForecast>) = PlanSummary(
            byReadiness = list.groupingBy { it.readiness }.eachCount(),
            fuelToDeliverL = list.sumOf { it.fuelNeededL },
            totalOutageH = list.maxOfOrNull { it.requiredH.toInt() } ?: 0,
            onBackupNow = list.count { it.telemetry?.gridOnline == false },
        )
    }
}

enum class ForecastSort(val label: String) {
    MARGIN_ASC("Найменший запас"),
    OUTAGE_DESC("Найдовші відключення"),
    NAME("За назвою"),
}

data class ForecastFilter(
    val readiness: Readiness? = null,
    val category: Int? = null,
    val sort: ForecastSort = ForecastSort.MARGIN_ASC,
) {
    fun apply(list: List<FacilityForecast>): List<FacilityForecast> {
        val filtered = list.filter {
            (readiness == null || it.readiness == readiness) && (category == null || it.facility.category == category)
        }
        return when (sort) {
            ForecastSort.MARGIN_ASC -> filtered.sortedBy { it.marginH }
            ForecastSort.OUTAGE_DESC -> filtered.sortedByDescending { it.requiredH }
            ForecastSort.NAME -> filtered.sortedBy { it.facility.name }
        }
    }
}
