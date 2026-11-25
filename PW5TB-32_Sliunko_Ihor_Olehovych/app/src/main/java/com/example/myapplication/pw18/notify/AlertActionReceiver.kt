package com.example.myapplication.pw18.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.myapplication.pw18.notify.AlertIntents.toPayload

/**
 * Обробник кнопок сповіщення. Працює без відкриття застосунку: змінює стан тривоги
 * в AlertRepository (екран отримає зміни через StateFlow) і оновлює шторку.
 */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val dispatcher = AlertNotifier.dispatcher(context)
        if (intent.action == AlertIntents.ACTION_STOP_MONITOR) {
            dispatcher.stopMonitor()
            return
        }
        val payload = intent.extras?.toPayload() ?: return
        when (intent.action) {
            AlertIntents.ACTION_ACK -> dispatcher.acknowledge(payload.id)
            AlertIntents.ACTION_SNOOZE -> dispatcher.snooze(payload)
            AlertIntents.ACTION_DISMISS -> dispatcher.dismiss(payload.id)
        }
    }
}
