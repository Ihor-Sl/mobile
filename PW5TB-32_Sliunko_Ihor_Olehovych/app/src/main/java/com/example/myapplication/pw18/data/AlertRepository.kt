package com.example.myapplication.pw18.data

import com.example.myapplication.pw18.domain.Alert
import com.example.myapplication.pw18.domain.AlertBook
import com.example.myapplication.pw18.domain.AlertCategory
import com.example.myapplication.pw18.domain.AlertPayload
import com.example.myapplication.pw18.domain.AlertStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * Єдине джерело стану сповіщень у процесі. Екран, BroadcastReceiver дій і Worker
 * змінюють той самий StateFlow, тому дія в шторці одразу відображається в UI і навпаки.
 */
class AlertRepository(private val clock: () -> Long = System::currentTimeMillis) {
    private val _book = MutableStateFlow(AlertBook())
    val book: StateFlow<AlertBook> = _book.asStateFlow()

    private val _enabled = MutableStateFlow(AlertCategory.entries.toSet())
    val enabled: StateFlow<Set<AlertCategory>> = _enabled.asStateFlow()

    private val _monitoring = MutableStateFlow(false)
    val monitoring: StateFlow<Boolean> = _monitoring.asStateFlow()

    /** Тривога, яку потрібно показати на екрані після натискання «Деталі» у сповіщенні. */
    private val _focus = MutableStateFlow<Int?>(null)
    val focus: StateFlow<Int?> = _focus.asStateFlow()

    fun raise(payload: AlertPayload): Alert {
        val now = clock()
        val updated = _book.updateAndGet { it.raise(payload, now) }
        return updated.alerts.first()
    }

    fun ensure(payload: AlertPayload): Alert {
        val now = clock()
        return _book.updateAndGet { it.restore(payload, now) }.find(payload.id)!!
    }

    fun setStatus(id: Int, status: AlertStatus, snoozedUntil: Long? = null): Alert? {
        val now = clock()
        return _book.updateAndGet { it.setStatus(id, status, now, snoozedUntil) }.find(id)
    }

    fun updateText(id: Int, text: String): Alert? =
        _book.updateAndGet { book -> book.change(id) { it.copy(text = text) } }.find(id)

    fun markPosted(id: Int, posted: Boolean): Alert? =
        _book.updateAndGet { book -> book.change(id) { it.copy(posted = posted) } }.find(id)

    fun setEnabled(category: AlertCategory, on: Boolean) {
        _enabled.update { if (on) it + category else it - category }
    }

    fun setMonitoring(on: Boolean) { _monitoring.value = on }

    fun focus(id: Int?) { _focus.value = id }

    fun clearClosed() {
        _book.update { book -> book.copy(alerts = book.alerts.filter { it.status == AlertStatus.ACTIVE || it.status == AlertStatus.SNOOZED }) }
    }

    companion object {
        /** Екземпляр процесу: його використовують MainActivity, ViewModel, Receiver і Worker. */
        val shared = AlertRepository()
    }
}
