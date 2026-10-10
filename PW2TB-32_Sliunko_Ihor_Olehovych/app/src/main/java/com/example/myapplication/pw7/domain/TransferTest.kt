package com.example.myapplication.pw7.domain

/** Крок випробування автоматичного введення резерву (АВР) та його тривалість у симуляції. */
enum class TransferStep(val title: String, val durationMs: Long) {
    GRID_OFF("Відключення вводу мережі", 800),
    UPS_PICKUP("ДБЖ підхоплює навантаження", 700),
    GENERATOR_START("Пуск ДГУ", 1_200),
    SYNC("Вихід ДГУ на номінальні оберти", 1_000),
    TRANSFER("Переведення навантаження на ДГУ", 800),
    GRID_RETURN("Повернення на мережу", 900),
}

/** Стан випробування АВР для UI. */
sealed interface TransferTestState {
    data object Idle : TransferTestState

    /** Тест триває: [step] — поточний крок, [startAttempt] — номер спроби пуску ДГУ. */
    data class Running(val step: TransferStep, val startAttempt: Int) : TransferTestState

    data class Passed(val durationSeconds: Double, val startAttempts: Int) : TransferTestState

    data class Failed(val step: TransferStep, val reason: String) : TransferTestState
}

/** Правила випробування. */
object TransferTestRules {
    /** Кількість спроб пуску ДГУ, після яких контролер фіксує відмову. */
    const val MAX_START_ATTEMPTS = 3
}
