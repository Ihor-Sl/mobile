package com.example.myapplication.pw18.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.myapplication.pw18.domain.AlertCategory
import com.example.myapplication.pw18.domain.AlertPayload
import com.example.myapplication.pw18.domain.AlertSource
import com.example.myapplication.sensors.SensorChannel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Планування через WorkManager: задача переживає закриття екрана й перезапуск процесу,
 * а після затримки AlertWorker показує сповіщення.
 */
class WorkAlertScheduler(context: Context) : AlertScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun scheduleReminder(payload: AlertPayload, delayMs: Long) {
        workManager.enqueue(request(payload, delayMs, snooze = false))
    }

    /** Унікальна робота на тривогу: повторне «Відкласти» замінює попередній таймер. */
    override fun scheduleSnooze(payload: AlertPayload, delayMs: Long) {
        workManager.enqueueUniqueWork("snooze_${payload.id}", ExistingWorkPolicy.REPLACE, request(payload, delayMs, snooze = true))
    }

    override fun pending(): Flow<List<ScheduledAlert>> =
        workManager.getWorkInfosByTagFlow(TAG).map { infos ->
            infos.filter { it.state == WorkInfo.State.ENQUEUED }.map { info ->
                ScheduledAlert(
                    title = info.tags.firstOrNull { it.startsWith(TITLE_TAG) }?.removePrefix(TITLE_TAG).orEmpty(),
                    dueAt = info.nextScheduleTimeMillis,
                    snooze = SNOOZE_TAG in info.tags,
                )
            }.sortedBy { it.dueAt }
        }

    override fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG)
    }

    private fun request(payload: AlertPayload, delayMs: Long, snooze: Boolean) =
        OneTimeWorkRequestBuilder<AlertWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(payload.toData(snooze))
            .addTag(TAG)
            .addTag(TITLE_TAG + payload.title)
            .apply { if (snooze) addTag(SNOOZE_TAG) }
            .build()

    companion object {
        const val TAG = "pw5_alert"
        private const val TITLE_TAG = "title:"
        private const val SNOOZE_TAG = "snooze"
    }
}

/** Фонова задача: показує нагадування або повертає відкладену тривогу. */
class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val payload = inputData.toPayload() ?: return Result.failure()
        val dispatcher = AlertNotifier.dispatcher(applicationContext)
        if (inputData.getBoolean(KEY_SNOOZE, false)) {
            dispatcher.reactivate(payload)
        } else {
            dispatcher.raise(payload.category, payload.title, payload.text, AlertSource.SCHEDULE)
        }
        return Result.success()
    }
}

private const val KEY_ID = "id"
private const val KEY_CATEGORY = "category"
private const val KEY_TITLE = "title"
private const val KEY_TEXT = "text"
private const val KEY_SOURCE = "source"
private const val KEY_CHANNEL = "channel"
private const val KEY_SNOOZE = "snooze"

private fun AlertPayload.toData(snooze: Boolean): Data = workDataOf(
    KEY_ID to id,
    KEY_CATEGORY to category.name,
    KEY_TITLE to title,
    KEY_TEXT to text,
    KEY_SOURCE to source.name,
    KEY_CHANNEL to channel?.name,
    KEY_SNOOZE to snooze,
)

private fun Data.toPayload(): AlertPayload? {
    val category = getString(KEY_CATEGORY)?.let(AlertCategory::valueOf) ?: return null
    return AlertPayload(
        id = getInt(KEY_ID, 0),
        category = category,
        title = getString(KEY_TITLE).orEmpty(),
        text = getString(KEY_TEXT).orEmpty(),
        source = getString(KEY_SOURCE)?.let(AlertSource::valueOf) ?: AlertSource.SCHEDULE,
        channel = getString(KEY_CHANNEL)?.let(SensorChannel::valueOf),
    )
}
