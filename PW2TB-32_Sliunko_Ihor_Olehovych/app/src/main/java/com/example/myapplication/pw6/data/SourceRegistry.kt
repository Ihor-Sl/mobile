package com.example.myapplication.pw6.data

import kotlinx.coroutines.delay

/** Зареєстроване резервне джерело. */
data class RegisteredSource(
    val inventory: String,
    val type: SourceType,
    val powerKw: Double,
    val capacity: Double,
    val criticalLoadKw: Double,
    val engineerEmail: String,
    val serviceDate: String,
    val autonomyHours: Double,
)

sealed interface RegistrationResult {
    data class Success(val source: RegisteredSource) : RegistrationResult
    data class Failure(val message: String) : RegistrationResult
}

/**
 * Імітація реєстру обладнання на сервері.
 * Затримки моделюють мережеві запити: під час них UI показує стан перевірки / завантаження.
 */
object SourceRegistry {
    private const val CHECK_DELAY_MS = 600L
    private const val SAVE_DELAY_MS = 1_500L

    /** Номери, що вже є в реєстрі (для перевірки унікальності). */
    private val inventories = mutableSetOf("DGU-0001", "DGU-0007", "UPS-0001", "UPS-0012")

    suspend fun isInventoryTaken(inventory: String): Boolean {
        delay(CHECK_DELAY_MS)
        return inventory.trim().uppercase() in inventories
    }

    suspend fun register(form: SourceForm): RegistrationResult {
        delay(SAVE_DELAY_MS)
        val inventory = form.inventory.trim().uppercase()
        // Повторна перевірка на «сервері»: номер міг зайняти інший оператор, поки заповнювалась форма
        if (!inventories.add(inventory)) {
            return RegistrationResult.Failure("Номер $inventory уже зареєстровано")
        }
        val capacity = SourceFormValidator.parseNumber(form.capacity)!!
        val load = SourceFormValidator.parseNumber(form.criticalLoad)!!
        return RegistrationResult.Success(
            RegisteredSource(
                inventory = inventory,
                type = form.type,
                powerKw = SourceFormValidator.parseNumber(form.power)!!,
                capacity = capacity,
                criticalLoadKw = load,
                engineerEmail = form.email.trim(),
                serviceDate = form.serviceDate.trim(),
                autonomyHours = SourceFormValidator.estimateAutonomyHours(form.type, capacity, load),
            )
        )
    }
}
