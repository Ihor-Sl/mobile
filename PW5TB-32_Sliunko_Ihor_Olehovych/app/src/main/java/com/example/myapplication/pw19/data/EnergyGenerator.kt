package com.example.myapplication.pw19.data

import com.example.myapplication.pw19.domain.EnergyHour
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Генератор погодинного обліку лікарні з резервним живленням.
 *
 * Навантаження: база 95 кВт + денний максимум до +45 кВт, у вихідні на 15 % менше,
 * плюс детермінований шум ±4 кВт. Графік відключень повторюється кожні 3 доби:
 * 08:00–12:00, 18:00–21:00, без відключень. Першу годину відключення 20 % енергії дає АКБ (ДБЖ),
 * поки ДГУ запускається й синхронізується, далі все навантаження несе ДГУ.
 */
class EnergyGenerator(private val zone: TimeZone = TimeZone.getDefault()) {

    fun hour(timeMs: Long): EnergyHour {
        val start = timeMs - Math.floorMod(timeMs, HOUR_MS)
        val (day, hour) = local(start)
        val daytime = if (hour in 6..21) sin(PI * (hour - 6) / 16) else 0.0
        val weekend = Math.floorMod(day + 3, 7L) >= 5 // 01.01.1970 — четвер
        val noise = Random(start / HOUR_MS).nextDouble(-NOISE_KW, NOISE_KW)
        val load = (BASE_KW + DAY_EXTRA_KW * daytime) * (if (weekend) WEEKEND_FACTOR else 1.0) + noise
        return when {
            !isOutage(start) -> EnergyHour(start, hour, load, 0.0, 0.0)
            !isOutage(start - HOUR_MS) -> EnergyHour(start, hour, 0.0, load * (1 - UPS_SHARE), load * UPS_SHARE)
            else -> EnergyHour(start, hour, 0.0, load, 0.0)
        }
    }

    /** Історія з [hours] записів, остання — година, що містить [endMs]. */
    fun history(endMs: Long, hours: Int = 720): List<EnergyHour> =
        (hours - 1 downTo 0).map { hour(endMs - it * HOUR_MS) }

    fun isOutage(timeMs: Long): Boolean {
        val (day, hour) = local(timeMs)
        return when (Math.floorMod(day, 3L)) {
            0L -> hour in 8..11
            1L -> hour in 18..20
            else -> false
        }
    }

    /** Номер доби та година за місцевим часом. */
    private fun local(timeMs: Long): Pair<Long, Int> {
        val localMs = timeMs + zone.getOffset(timeMs)
        return Math.floorDiv(localMs, DAY_MS) to (Math.floorMod(localMs, DAY_MS) / HOUR_MS).toInt()
    }

    companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
        const val BASE_KW = 95.0
        const val DAY_EXTRA_KW = 45.0
        const val WEEKEND_FACTOR = 0.85
        const val NOISE_KW = 4.0
        const val UPS_SHARE = 0.2
    }
}
