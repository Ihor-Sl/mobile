package com.example.myapplication.pw8.domain

import com.example.myapplication.pw8.data.local.EventType
import com.example.myapplication.pw8.data.local.PowerEventEntity
import com.example.myapplication.pw8.data.local.ReserveSourceEntity
import com.example.myapplication.pw8.data.local.SourceKind
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Поля форми події. */
enum class EventField { SOURCE, DATE, DURATION, LOAD, FUEL, NOTE }

/** Чернетка події з форми (текстові значення). id = 0 — нова подія. */
data class EventDraft(
    val id: Long = 0,
    val remoteId: String? = null,
    val sourceId: Long? = null,
    val type: EventType = EventType.OUTAGE,
    val dateTime: String = "",
    val durationMin: String = "",
    val avgLoadKw: String = "",
    val fuelUsedL: String = "",
    val note: String = "",
)

/**
 * Базова валідація введених значень перед записом у БД.
 * Частина правил залежить від даних іншої таблиці — потужності та типу обраного джерела.
 */
object EventValidator {
    const val MAX_DURATION_MIN = 7 * 24 * 60
    const val MAX_NOTE_LENGTH = 200
    /** Допустиме короткочасне перевантаження джерела. */
    const val OVERLOAD_FACTOR = 1.1
    /** Максимальна правдоподібна питома витрата палива ДГУ, л/кВт·год. */
    const val MAX_SPECIFIC_FUEL = 0.4

    private const val PATTERN = "dd.MM.yyyy HH:mm"

    fun formatDateTime(millis: Long): String = SimpleDateFormat(PATTERN, Locale.US).format(Date(millis))

    /** Строгий розбір «дд.мм.рррр гг:хх»: неіснуючі дати (31.02) та зайві символи відхиляються. */
    fun parseDateTime(text: String): Long? {
        val format = SimpleDateFormat(PATTERN, Locale.US).apply { isLenient = false }
        val position = ParsePosition(0)
        val trimmed = text.trim()
        val date = format.parse(trimmed, position) ?: return null
        return if (position.index == trimmed.length) date.time else null
    }

    private fun number(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

    fun validate(draft: EventDraft, source: ReserveSourceEntity?, now: Long): Map<EventField, String> = buildMap {
        if (source == null) put(EventField.SOURCE, "Оберіть джерело")

        val started = parseDateTime(draft.dateTime)
        when {
            draft.dateTime.isBlank() -> put(EventField.DATE, "Введіть дату та час")
            started == null -> put(EventField.DATE, "Формат дд.мм.рррр гг:хх")
            started > now -> put(EventField.DATE, "Подія не може бути в майбутньому")
        }

        val duration = draft.durationMin.trim().toIntOrNull()
        if (duration == null || duration !in 1..MAX_DURATION_MIN) {
            put(EventField.DURATION, "Ціле число від 1 до $MAX_DURATION_MIN хв")
        }

        val load = number(draft.avgLoadKw)
        when {
            load == null || load <= 0 -> put(EventField.LOAD, "Додатне число, кВт")
            source != null && load > source.ratedPowerKw * OVERLOAD_FACTOR ->
                put(EventField.LOAD, "Не більше 110 %% потужності (%.0f кВт)".format(source.ratedPowerKw * OVERLOAD_FACTOR))
        }

        val fuel = number(draft.fuelUsedL)
        when {
            fuel == null || fuel < 0 -> put(EventField.FUEL, "Невід'ємне число, л")
            source?.kind == SourceKind.UPS && fuel > 0 -> put(EventField.FUEL, "ДБЖ не споживає паливо — вкажіть 0")
            source?.kind == SourceKind.GENERATOR && duration != null && duration > 0 -> {
                val maxFuel = MAX_SPECIFIC_FUEL * source.ratedPowerKw * duration / 60.0
                if (fuel > maxFuel) put(EventField.FUEL, "Неправдоподібно багато: максимум %.0f л".format(maxFuel))
            }
        }

        if (draft.note.length > MAX_NOTE_LENGTH) put(EventField.NOTE, "Не більше $MAX_NOTE_LENGTH символів")
    }

    /** Перетворення коректної чернетки на сутність Room. */
    fun toEntity(draft: EventDraft): PowerEventEntity = PowerEventEntity(
        id = draft.id,
        sourceId = requireNotNull(draft.sourceId),
        type = draft.type,
        startedAt = requireNotNull(parseDateTime(draft.dateTime)),
        durationMin = draft.durationMin.trim().toInt(),
        avgLoadKw = number(draft.avgLoadKw)!!,
        fuelUsedL = number(draft.fuelUsedL)!!,
        note = draft.note.trim(),
        remoteId = draft.remoteId,
    )

    /** Заповнення форми для редагування наявної події. */
    fun fromEntity(event: PowerEventEntity): EventDraft = EventDraft(
        id = event.id,
        remoteId = event.remoteId,
        sourceId = event.sourceId,
        type = event.type,
        dateTime = formatDateTime(event.startedAt),
        durationMin = event.durationMin.toString(),
        avgLoadKw = trimNumber(event.avgLoadKw),
        fuelUsedL = trimNumber(event.fuelUsedL),
        note = event.note,
    )

    private fun trimNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}
