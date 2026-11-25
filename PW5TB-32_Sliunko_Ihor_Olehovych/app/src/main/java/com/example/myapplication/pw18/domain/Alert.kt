package com.example.myapplication.pw18.domain

import com.example.myapplication.sensors.SensorChannel

/** Категорія події; кожна має власний канал сповіщень із пріоритетом і звуком. */
enum class AlertCategory(val label: String, val channelId: String, val description: String) {
    ALARM("Аварії", "pw5_alarm", "Відключення мережі, збій АВР, перегрів АКБ: звук і спливаюче сповіщення"),
    WARNING("Попередження", "pw5_warning", "Наближення параметрів до порогу, низький рівень палива"),
    REMINDER("Нагадування", "pw5_reminder", "Планові перевірки ДГУ та АКБ за розкладом"),
}

enum class AlertStatus(val label: String) {
    ACTIVE("Активна"),
    SNOOZED("Відкладена"),
    ACKNOWLEDGED("Підтверджена"),
    DISMISSED("Закрита"),
}

enum class AlertSource(val label: String) {
    SENSOR("Сенсор"),
    OPERATOR("Оператор"),
    SCHEDULE("Розклад"),
}

/**
 * Тривога в журналі застосунку. Ідентифікатор збігається з id системного сповіщення,
 * тому дія зі шторки та кнопка на екрані змінюють один і той самий запис.
 */
data class Alert(
    val id: Int,
    val category: AlertCategory,
    val title: String,
    val text: String,
    val source: AlertSource,
    val createdAt: Long,
    val channel: SensorChannel? = null,
    val updatedAt: Long = createdAt,
    val status: AlertStatus = AlertStatus.ACTIVE,
    val snoozeCount: Int = 0,
    val snoozedUntil: Long? = null,
    /** Чи показано системне сповіщення (категорія увімкнена й дозвіл надано). */
    val posted: Boolean = false,
) {
    fun toPayload() = AlertPayload(id, category, title, text, source, channel)
}

/** Мінімальні дані для відновлення тривоги в Intent або WorkManager, якщо процес було завершено. */
data class AlertPayload(
    val id: Int,
    val category: AlertCategory,
    val title: String,
    val text: String,
    val source: AlertSource,
    val channel: SensorChannel? = null,
)

/** Незмінний журнал тривог: кожна операція повертає новий стан (зручно для StateFlow і тестів). */
data class AlertBook(val alerts: List<Alert> = emptyList(), val nextId: Int = FIRST_ID) {
    val active: List<Alert> get() = alerts.filter { it.status == AlertStatus.ACTIVE }

    fun find(id: Int): Alert? = alerts.firstOrNull { it.id == id }

    fun raise(payload: AlertPayload, now: Long): AlertBook {
        val alert = Alert(nextId, payload.category, payload.title, payload.text, payload.source, now, payload.channel)
        return copy(alerts = (listOf(alert) + alerts).take(MAX_ALERTS), nextId = nextId + 1)
    }

    /** Повертає запис, якого вже немає в пам'яті (дія прийшла після перезапуску процесу). */
    fun restore(payload: AlertPayload, now: Long): AlertBook {
        if (find(payload.id) != null) return this
        val alert = Alert(payload.id, payload.category, payload.title, payload.text, payload.source, now, payload.channel)
        return copy(alerts = (listOf(alert) + alerts).take(MAX_ALERTS), nextId = maxOf(nextId, payload.id + 1))
    }

    fun change(id: Int, transform: (Alert) -> Alert): AlertBook =
        copy(alerts = alerts.map { if (it.id == id) transform(it) else it })

    fun setStatus(id: Int, status: AlertStatus, now: Long, snoozedUntil: Long? = null): AlertBook = change(id) {
        it.copy(
            status = status,
            updatedAt = now,
            snoozedUntil = snoozedUntil,
            snoozeCount = it.snoozeCount + if (status == AlertStatus.SNOOZED) 1 else 0,
        )
    }

    companion object {
        /** Id 1 і 2 зайняті підсумковим сповіщенням групи та сповіщенням моніторингу. */
        const val FIRST_ID = 100
        const val MAX_ALERTS = 30
    }
}
