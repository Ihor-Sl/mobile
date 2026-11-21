package com.example.myapplication.pw13.data

import com.example.myapplication.pw13.domain.SensorFaultException
import com.example.myapplication.pw13.domain.SensorId
import com.example.myapplication.pw13.domain.SensorSample
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/** Сценарій роботи об'єкта, який задає оператор на екрані. */
enum class PowerScenario(val label: String) {
    NORMAL("Норма"),
    OUTAGE("Відключення"),
    LOW_FUEL("Мало палива"),
}

/**
 * Імітація датчиків. Кожен [stream] — «холодний» Flow: генерація починається лише під час збирання.
 * Стан палива та АКБ зберігається тут, тому перезапуск потоку після збою не скидає показники.
 */
class SimulatedPowerSensors(private val periodMs: Long = 1_000) {
    private val _scenario = MutableStateFlow(PowerScenario.NORMAL)
    val scenario: StateFlow<PowerScenario> = _scenario.asStateFlow()

    private val pendingFaults = ConcurrentHashMap<SensorId, Int>()
    private val ticks = ConcurrentHashMap<SensorId, Long>()

    @Volatile private var fuel = INITIAL_FUEL
    @Volatile private var battery = 100.0

    fun setScenario(value: PowerScenario) {
        when (value) {
            PowerScenario.NORMAL -> { fuel = INITIAL_FUEL; battery = 100.0 }
            PowerScenario.OUTAGE -> Unit
            PowerScenario.LOW_FUEL -> fuel = minOf(fuel, LOW_FUEL)
        }
        _scenario.value = value
    }

    /** Наступні [count] зчитувань датчика завершаться помилкою. */
    fun injectFault(id: SensorId, count: Int) { pendingFaults[id] = count }

    fun clearFault(id: SensorId) { pendingFaults.remove(id) }

    fun stream(id: SensorId): Flow<SensorSample> = flow {
        while (true) {
            val faults = pendingFaults[id] ?: 0
            if (faults > 0) {
                pendingFaults[id] = faults - 1
                throw SensorFaultException(id)
            }
            val tick = ticks.merge(id, 1L) { a, b -> a + b } ?: 1L
            emit(SensorSample(id, valueAt(id, tick), tick))
            delay(periodMs)
        }
    }

    private fun valueAt(id: SensorId, tick: Long): Double {
        val onGrid = _scenario.value == PowerScenario.NORMAL
        return when (id) {
            // Кожне 7-ме вимірювання — навмисний збій (-1 В), який відкидає оператор filter
            SensorId.GRID -> when {
                !onGrid -> 0.0
                tick % 7 == 0L -> -1.0
                else -> 225 + 8 * sin(tick / 3.0)
            }
            SensorId.LOAD -> 55 + 8 * sin(tick / 5.0)
            SensorId.FUEL -> {
                if (!onGrid) fuel = (fuel - BURN_PER_TICK).coerceAtLeast(0.0)
                fuel
            }
            SensorId.BATTERY -> {
                battery = when {
                    onGrid -> (battery + 0.5).coerceAtMost(100.0)
                    fuel <= 0 -> (battery - 2.0).coerceAtLeast(0.0)
                    else -> battery
                }
                battery
            }
        }
    }

    private companion object {
        const val INITIAL_FUEL = 420.0
        const val LOW_FUEL = 18.0

        /** Прискорена модель: за секунду демонстрації витрачається 1,5 л. */
        const val BURN_PER_TICK = 1.5
    }
}
