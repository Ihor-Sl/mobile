package com.example.myapplication.pw18.notify

import com.example.myapplication.pw18.data.AlertRepository
import com.example.myapplication.pw18.domain.Alert
import com.example.myapplication.pw18.domain.AlertCategory
import com.example.myapplication.pw18.domain.AlertPayload
import com.example.myapplication.pw18.domain.AlertSource
import com.example.myapplication.pw18.domain.AlertStatus
import com.example.myapplication.sensors.SensorChannel
import kotlinx.coroutines.flow.Flow

/** Показ системних сповіщень (реалізація — NotificationManager, у тестах — підміна). */
interface AlertPoster {
    fun canPost(): Boolean
    fun post(alert: Alert, silent: Boolean = false)
    fun cancel(id: Int)
    fun updateSummary(posted: List<Alert>)
    fun showMonitor(lines: List<String>, activeCount: Int)
    fun cancelMonitor()
}

/** Відкладений показ: нагадування за розкладом і повтор після «Відкласти» (WorkManager). */
interface AlertScheduler {
    fun scheduleReminder(payload: AlertPayload, delayMs: Long)
    fun scheduleSnooze(payload: AlertPayload, delayMs: Long)
    fun pending(): Flow<List<ScheduledAlert>>
    fun cancelAll()
}

data class ScheduledAlert(val title: String, val dueAt: Long, val snooze: Boolean)

/**
 * Уся логіка життєвого циклу тривоги в одному місці; її викликають ViewModel (кнопки на екрані),
 * AlertActionReceiver (кнопки в сповіщенні) і AlertWorker (спрацювання таймера).
 */
class AlertDispatcher(
    val repository: AlertRepository,
    private val poster: AlertPoster,
    private val scheduler: AlertScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun raise(
        category: AlertCategory,
        title: String,
        text: String,
        source: AlertSource,
        channel: SensorChannel? = null,
    ): Alert = publish(repository.raise(AlertPayload(0, category, title, text, source, channel)))

    fun acknowledge(id: Int) {
        repository.setStatus(id, AlertStatus.ACKNOWLEDGED)
        poster.cancel(id)
        refreshSummary()
    }

    /** Сповіщення прибирається зі шторки, а WorkManager покаже його знову через [delayMs]. */
    fun snooze(payload: AlertPayload, delayMs: Long = SNOOZE_MS) {
        repository.ensure(payload)
        repository.setStatus(payload.id, AlertStatus.SNOOZED, snoozedUntil = clock() + delayMs)
        poster.cancel(payload.id)
        scheduler.scheduleSnooze(payload, delayMs)
        refreshSummary()
    }

    /** Користувач змахнув сповіщення: тривога лишається в журналі як закрита. */
    fun dismiss(id: Int) {
        repository.setStatus(id, AlertStatus.DISMISSED)
        refreshSummary()
    }

    /** Повтор відкладеної тривоги з тим самим id. */
    fun reactivate(payload: AlertPayload) {
        val current = repository.ensure(payload)
        if (current.status == AlertStatus.ACKNOWLEDGED) return
        repository.setStatus(payload.id, AlertStatus.ACTIVE)?.let(::publish)
    }

    fun scheduleReminder(title: String, text: String, delayMs: Long) {
        scheduler.scheduleReminder(AlertPayload(0, AlertCategory.REMINDER, title, text, AlertSource.SCHEDULE), delayMs)
    }

    /** Оновлення тексту активної тривоги актуальним значенням сенсора без повторного звуку. */
    fun updateLive(id: Int, text: String) {
        val alert = repository.updateText(id, text) ?: return
        if (alert.status == AlertStatus.ACTIVE && alert.posted && poster.canPost()) poster.post(alert, silent = true)
    }

    /** Вимкнення категорії прибирає її сповіщення зі шторки; записи лишаються в журналі. */
    fun setCategoryEnabled(category: AlertCategory, on: Boolean) {
        repository.setEnabled(category, on)
        repository.book.value.active.filter { it.category == category }.forEach { alert ->
            if (on) publish(alert) else {
                poster.cancel(alert.id)
                repository.markPosted(alert.id, false)
            }
        }
        refreshSummary()
    }

    fun showMonitor(lines: List<String>) {
        if (poster.canPost()) poster.showMonitor(lines, repository.book.value.active.size)
    }

    fun stopMonitor() {
        repository.setMonitoring(false)
        poster.cancelMonitor()
    }

    private fun publish(alert: Alert): Alert {
        val allowed = alert.category in repository.enabled.value && poster.canPost()
        val marked = repository.markPosted(alert.id, allowed) ?: alert
        if (allowed) poster.post(marked)
        refreshSummary()
        return marked
    }

    private fun refreshSummary() {
        poster.updateSummary(repository.book.value.active.filter { it.posted })
    }

    companion object {
        const val SNOOZE_MS = 60_000L
    }
}
