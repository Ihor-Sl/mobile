package com.example.myapplication.facilities

import java.text.Collator
import java.util.Locale

/** Параметр сортування списку об'єктів. */
enum class SortKey(val label: String) {
    NAME("Назва"),
    TYPE("Тип"),
    POWER("Потужність"),
    AUTONOMY("Автономність"),
    UPDATED("Оновлення"),
    DISTANCE("Відстань"),
}

data class SortRule(val key: SortKey, val descending: Boolean = false)

/**
 * Багаторівневий запит: усі фільтри поєднуються через «І», значення всередині одного фільтра — через «АБО».
 * Порожня множина означає, що фільтр вимкнено.
 */
data class FacilityQuery(
    val text: String = "",
    val types: Set<FacilityType> = emptySet(),
    val statuses: Set<PowerStatus> = emptySet(),
    val readiness: Set<Readiness> = emptySet(),
    val minPowerKw: Double = 0.0,
    val primary: SortRule = SortRule(SortKey.NAME),
    val secondary: SortRule? = null,
) {
    val activeFilters: Int
        get() = listOf(text.isNotBlank(), types.isNotEmpty(), statuses.isNotEmpty(), readiness.isNotEmpty(), minPowerKw > 0)
            .count { it }
}

fun <T> Set<T>.toggled(value: T): Set<T> = if (value in this) this - value else this + value

/** Застосовує фільтри й сортування; [origin] потрібен лише для сортування за відстанню. */
fun List<Facility>.applyQuery(query: FacilityQuery, origin: GeoPoint? = null): List<Facility> {
    val needle = query.text.trim().lowercase()
    val filtered = filter { f ->
        (needle.isEmpty() || f.name.lowercase().contains(needle) || f.address.lowercase().contains(needle)) &&
            (query.types.isEmpty() || f.type in query.types) &&
            (query.statuses.isEmpty() || f.status in query.statuses) &&
            (query.readiness.isEmpty() || f.readiness in query.readiness) &&
            f.reserveKw >= query.minPowerKw
    }
    var comparator = comparatorFor(query.primary, origin)
    query.secondary?.let { comparator = comparator.then(comparatorFor(it, origin)) }
    // Ідентифікатор робить порядок передбачуваним, коли всі критерії рівні
    return filtered.sortedWith(comparator.thenBy { it.id })
}

private fun comparatorFor(rule: SortRule, origin: GeoPoint?): Comparator<Facility> {
    // Collator враховує український алфавіт (є, і, ї, ґ), на відміну від порівняння кодів символів
    val collator = Collator.getInstance(Locale.forLanguageTag("uk"))
    val base: Comparator<Facility> = when (rule.key) {
        SortKey.NAME -> compareBy(collator) { it.name }
        SortKey.TYPE -> compareBy(collator) { it.type.label }
        SortKey.POWER -> compareBy { it.reserveKw }
        SortKey.AUTONOMY -> compareBy { it.autonomyHours }
        SortKey.UPDATED -> compareBy { it.updatedAt }
        // Без геолокації відстані невідомі, тому цей критерій не змінює порядок
        SortKey.DISTANCE -> compareBy { f -> origin?.let { distanceKm(it, f.location) } ?: 0.0 }
    }
    return if (rule.descending) base.reversed() else base
}
