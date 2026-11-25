package com.example.myapplication.pw19.domain

/** Групи споживачів лікарні; частки навантаження вдень (07–20) і вночі різні. */
enum class ConsumerZone(val label: String, val dayShare: Double, val nightShare: Double) {
    SURGERY("Операційний блок", 0.24, 0.10),
    ICU("Реанімація", 0.16, 0.26),
    LAB("Лабораторія", 0.12, 0.05),
    LIGHTING("Освітлення", 0.10, 0.18),
    HVAC("Вентиляція", 0.22, 0.24),
    PUMPS("Насосна", 0.16, 0.17),
    ;

    fun share(hourOfDay: Int) = if (hourOfDay in 7..20) dayShare else nightShare
}

/** Погодинний запис обліку: енергія від кожного джерела за годину, кВт·год. */
data class EnergyHour(
    val timeMs: Long,
    val hourOfDay: Int,
    val gridKwh: Double,
    val gensetKwh: Double,
    val batteryKwh: Double,
) {
    val total: Double get() = gridKwh + gensetKwh + batteryKwh
    val outage: Boolean get() = gridKwh == 0.0
}

/** Часовий інтервал відображення та розмір стовпчика в годинах. */
enum class TimeInterval(val label: String, val hours: Int, val bucketHours: Int) {
    DAY("Доба", 24, 1),
    WEEK("Тиждень", 168, 4),
    MONTH("Місяць", 720, 24),
    ;

    val bucketCount: Int get() = hours / bucketHours
}

/** Один стовпчик графіка: сума енергії за [hours] годин від кожного джерела. */
data class EnergyBucket(
    val label: String,
    val startMs: Long,
    val hours: Int,
    val grid: Double,
    val genset: Double,
    val battery: Double,
) {
    val total: Double get() = grid + genset + battery
}

/** Додаткові показники інтервалу. */
data class EnergyStats(
    val total: Double,
    val average: Double,
    val peak: Double,
    val peakIndex: Int,
    val min: Double,
    val gensetShare: Double,
    val batteryShare: Double,
    val outageHours: Int,
    val fuelLiters: Double,
    val peakPowerKw: Double,
)

data class ZoneUsage(val zone: ConsumerZone, val kwh: Double, val share: Double)

/** Підготовка даних до візуалізації: агрегування, тренд і показники (без залежності від Android). */
object EnergyMath {
    /** Питома витрата дизельного палива ДГУ, л/кВт·год. */
    const val FUEL_PER_KWH = 0.27

    const val TREND_WINDOW = 3

    fun window(hours: List<EnergyHour>, interval: TimeInterval): List<EnergyHour> = hours.takeLast(interval.hours)

    /**
     * Групування з кінця ряду, щоб останній стовпчик завжди був повним:
     * доба — 24 стовпчики по 1 год, тиждень — 42 по 4 год, місяць — 30 по 24 год.
     */
    fun buckets(hours: List<EnergyHour>, interval: TimeInterval, label: (Long) -> String): List<EnergyBucket> =
        window(hours, interval).reversed().chunked(interval.bucketHours).reversed().map { chunk ->
            val ordered = chunk.reversed()
            EnergyBucket(
                label = label(ordered.first().timeMs),
                startMs = ordered.first().timeMs,
                hours = ordered.size,
                grid = ordered.sumOf { it.gridKwh },
                genset = ordered.sumOf { it.gensetKwh },
                battery = ordered.sumOf { it.batteryKwh },
            )
        }

    /** Ковзне середнє (лінія тренду) по тому самому набору даних, що й стовпчики. */
    fun movingAverage(values: List<Double>, window: Int = TREND_WINDOW): List<Double> =
        values.indices.map { i ->
            val from = (i - window + 1).coerceAtLeast(0)
            values.subList(from, i + 1).average()
        }

    fun stats(buckets: List<EnergyBucket>, hours: List<EnergyHour>): EnergyStats? {
        if (buckets.isEmpty() || hours.isEmpty()) return null
        val totals = buckets.map { it.total }
        val total = totals.sum()
        val genset = buckets.sumOf { it.genset }
        val peakIndex = totals.indices.maxBy { totals[it] }
        return EnergyStats(
            total = total,
            average = total / buckets.size,
            peak = totals[peakIndex],
            peakIndex = peakIndex,
            min = totals.min(),
            gensetShare = genset / total * 100,
            batteryShare = buckets.sumOf { it.battery } / total * 100,
            outageHours = hours.count { it.outage },
            fuelLiters = genset * FUEL_PER_KWH,
            peakPowerKw = hours.maxOf { it.total },
        )
    }

    fun zones(hours: List<EnergyHour>): List<ZoneUsage> {
        val total = hours.sumOf { it.total }
        return ConsumerZone.entries.map { zone ->
            val kwh = hours.sumOf { it.total * zone.share(it.hourOfDay) }
            ZoneUsage(zone, kwh, if (total == 0.0) 0.0 else kwh / total * 100)
        }
    }
}
