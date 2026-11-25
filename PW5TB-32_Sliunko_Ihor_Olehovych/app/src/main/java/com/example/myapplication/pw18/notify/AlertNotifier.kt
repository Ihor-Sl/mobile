package com.example.myapplication.pw18.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.R
import com.example.myapplication.pw18.domain.Alert
import com.example.myapplication.pw18.domain.AlertCategory
import com.example.myapplication.pw18.domain.AlertPayload
import com.example.myapplication.pw18.domain.AlertSource
import com.example.myapplication.sensors.SensorChannel

/** Дії та ключі Intent, спільні для сповіщень, AlertActionReceiver і MainActivity. */
object AlertIntents {
    const val ACTION_ACK = "com.example.myapplication.pw5.ACK"
    const val ACTION_SNOOZE = "com.example.myapplication.pw5.SNOOZE"
    const val ACTION_DISMISS = "com.example.myapplication.pw5.DISMISS"
    const val ACTION_STOP_MONITOR = "com.example.myapplication.pw5.STOP_MONITOR"
    const val ACTION_OPEN = "com.example.myapplication.pw5.OPEN_ALERT"

    const val EXTRA_ID = "alert_id"
    private const val EXTRA_CATEGORY = "alert_category"
    private const val EXTRA_TITLE = "alert_title"
    private const val EXTRA_TEXT = "alert_text"
    private const val EXTRA_SOURCE = "alert_source"
    private const val EXTRA_CHANNEL = "alert_channel"

    fun Intent.putPayload(payload: AlertPayload): Intent = apply {
        putExtra(EXTRA_ID, payload.id)
        putExtra(EXTRA_CATEGORY, payload.category.name)
        putExtra(EXTRA_TITLE, payload.title)
        putExtra(EXTRA_TEXT, payload.text)
        putExtra(EXTRA_SOURCE, payload.source.name)
        putExtra(EXTRA_CHANNEL, payload.channel?.name)
    }

    fun Bundle.toPayload(): AlertPayload? {
        val id = getInt(EXTRA_ID, -1).takeIf { it >= 0 } ?: return null
        return AlertPayload(
            id = id,
            category = AlertCategory.valueOf(getString(EXTRA_CATEGORY) ?: return null),
            title = getString(EXTRA_TITLE).orEmpty(),
            text = getString(EXTRA_TEXT).orEmpty(),
            source = getString(EXTRA_SOURCE)?.let(AlertSource::valueOf) ?: AlertSource.OPERATOR,
            channel = getString(EXTRA_CHANNEL)?.let(SensorChannel::valueOf),
        )
    }
}

/**
 * Показ сповіщень через NotificationManager. Кожна тривога має кнопки «Підтвердити»,
 * «Відкласти» та «Деталі»: перші дві обробляє AlertActionReceiver без відкриття застосунку,
 * «Деталі» відкриває екран ПЗ 18 із вибраною тривогою.
 */
class AlertNotifier(context: Context) : AlertPoster {
    private val app = context.applicationContext
    private val manager = NotificationManagerCompat.from(app)

    /** Канали створюються один раз (повторний виклик нічого не змінює); налаштування звуку далі належать користувачу. */
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = app.getSystemService(NotificationManager::class.java)
        AlertCategory.entries.forEach { category ->
            val channel = NotificationChannel(category.channelId, category.label, importance(category)).apply {
                description = category.description
                enableLights(category == AlertCategory.ALARM)
                lightColor = Color.RED
                enableVibration(category != AlertCategory.REMINDER)
            }
            system.createNotificationChannel(channel)
        }
        system.createNotificationChannel(
            NotificationChannel(MONITOR_CHANNEL, "Моніторинг", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Постійне сповіщення з актуальними показниками сенсорів"
                setShowBadge(false)
            }
        )
    }

    override fun canPost(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && manager.areNotificationsEnabled()
    }

    override fun post(alert: Alert, silent: Boolean) {
        if (!canPost()) return
        val payload = alert.toPayload()
        val builder = NotificationCompat.Builder(app, alert.category.channelId)
            .setSmallIcon(R.drawable.ic_stat_power)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
            .setPriority(priority(alert.category))
            .setCategory(if (alert.category == AlertCategory.REMINDER) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_ALARM)
            .setColor(accent(alert.category))
            .setWhen(alert.createdAt)
            .setShowWhen(true)
            .setGroup(GROUP_KEY)
            // Повторне оновлення тексту (актуальні дані сенсора) не відтворює звук ще раз
            .setOnlyAlertOnce(silent)
            .setSilent(silent)
            // Аварію не можна прибрати натисканням: її потрібно підтвердити
            .setAutoCancel(false)
            .setContentIntent(openPending(alert.id))
            .setDeleteIntent(actionPending(AlertIntents.ACTION_DISMISS, payload, 3))
            .addAction(0, "Підтвердити", actionPending(AlertIntents.ACTION_ACK, payload, 1))
            .addAction(0, "Відкласти 1 хв", actionPending(AlertIntents.ACTION_SNOOZE, payload, 2))
            .addAction(0, "Деталі", openPending(alert.id))
        notify(alert.id, builder)
    }

    override fun cancel(id: Int) = manager.cancel(id)

    /** Підсумок групи: на Android 7+ кілька тривог згортаються в одну групу зі списком. */
    override fun updateSummary(posted: List<Alert>) {
        if (posted.size < 2) {
            manager.cancel(SUMMARY_ID)
            return
        }
        val style = NotificationCompat.InboxStyle().setSummaryText("Активних тривог: ${posted.size}")
        posted.take(5).forEach { style.addLine("${it.category.label}: ${it.title}") }
        val builder = NotificationCompat.Builder(app, AlertCategory.ALARM.channelId)
            .setSmallIcon(R.drawable.ic_stat_power)
            .setContentTitle("Активних тривог: ${posted.size}")
            .setContentText(posted.joinToString { it.title })
            .setStyle(style)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(openPending(null))
        notify(SUMMARY_ID, builder)
    }

    /** Постійне тихе сповіщення з поточними показниками сенсорів (оновлюється без звуку). */
    override fun showMonitor(lines: List<String>, activeCount: Int) {
        val style = NotificationCompat.InboxStyle()
        lines.forEach(style::addLine)
        val stopIntent = Intent(app, AlertActionReceiver::class.java).setAction(AlertIntents.ACTION_STOP_MONITOR)
        val builder = NotificationCompat.Builder(app, MONITOR_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_power)
            .setContentTitle(if (activeCount == 0) "Моніторинг: відхилень немає" else "Моніторинг: активних тривог $activeCount")
            .setContentText(lines.joinToString(" · "))
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPending(null))
            .addAction(0, "Зупинити", PendingIntent.getBroadcast(app, MONITOR_ID, stopIntent, FLAGS))
        notify(MONITOR_ID, builder)
    }

    override fun cancelMonitor() = manager.cancel(MONITOR_ID)

    private fun notify(id: Int, builder: NotificationCompat.Builder) {
        // Явна перевірка дозволу POST_NOTIFICATIONS (Android 13+) перед кожним показом
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        manager.notify(id, builder.build())
    }

    /** requestCode унікальний для пари (тривога, дія), інакше PendingIntent різних кнопок збігалися б. */
    private fun actionPending(action: String, payload: AlertPayload, slot: Int): PendingIntent {
        val intent = Intent(app, AlertActionReceiver::class.java).setAction(action).apply { with(AlertIntents) { putPayload(payload) } }
        return PendingIntent.getBroadcast(app, payload.id * 10 + slot, intent, FLAGS)
    }

    private fun openPending(id: Int?): PendingIntent {
        val intent = Intent(app, MainActivity::class.java).apply {
            action = AlertIntents.ACTION_OPEN
            putExtra(AlertIntents.EXTRA_ID, id ?: -1)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(app, (id ?: 0) * 10 + 4, intent, FLAGS)
    }

    private fun importance(category: AlertCategory) = when (category) {
        AlertCategory.ALARM -> NotificationManager.IMPORTANCE_HIGH
        AlertCategory.WARNING, AlertCategory.REMINDER -> NotificationManager.IMPORTANCE_DEFAULT
    }

    /** Пріоритет для Android 7 і нижче, де ще немає каналів. */
    private fun priority(category: AlertCategory) = when (category) {
        AlertCategory.ALARM -> NotificationCompat.PRIORITY_HIGH
        AlertCategory.WARNING, AlertCategory.REMINDER -> NotificationCompat.PRIORITY_DEFAULT
    }

    private fun accent(category: AlertCategory) = when (category) {
        AlertCategory.ALARM -> 0xFFC62828.toInt()
        AlertCategory.WARNING -> 0xFFE08A00.toInt()
        AlertCategory.REMINDER -> 0xFF3F51B5.toInt()
    }

    companion object {
        const val GROUP_KEY = "pw5_alerts"
        const val MONITOR_CHANNEL = "pw5_monitor"
        const val SUMMARY_ID = 1
        const val MONITOR_ID = 2
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        /** Диспетчер для компонентів, що працюють поза екраном (Receiver, Worker). */
        fun dispatcher(context: Context) =
            AlertDispatcher(com.example.myapplication.pw18.data.AlertRepository.shared, AlertNotifier(context), WorkAlertScheduler(context))
    }
}
