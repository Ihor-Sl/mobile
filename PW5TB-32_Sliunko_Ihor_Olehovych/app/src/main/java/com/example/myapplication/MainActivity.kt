package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.myapplication.host.PracticeHost
import com.example.myapplication.host.PracticeWork
import com.example.myapplication.pw18.data.AlertRepository
import com.example.myapplication.pw18.notify.AlertIntents
import com.example.myapplication.pw18.notify.AlertNotifier
import com.example.myapplication.ui.theme.MyApplicationTheme

/**
 * Точка входу застосунку «Система аварійного електропостачання» (варіант 18).
 *
 * Практична робота №5 (сенсори, сповіщення, графіки та користувацькі компоненти для датчиків)
 * складається з чотирьох занять; кожне заняття виконано на просунутому рівні та винесено
 * в окремий пакет (pw17 … pw20). Перемикання між заняттями виконує [PracticeHost].
 */
class MainActivity : ComponentActivity() {
    /** Заняття, яке потрібно відкрити після натискання на сповіщення. */
    private var openRequest by mutableStateOf<PracticeWork?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Канали потрібно створити до першого сповіщення (Android 8+)
        AlertNotifier(this).ensureChannels()
        if (savedInstanceState == null) handleAlertIntent(intent)
        setContent {
            MyApplicationTheme {
                PracticeHost(openRequest = openRequest, onOpenHandled = { openRequest = null })
            }
        }
    }

    /** launchMode="singleTop": повторне натискання на сповіщення приходить сюди, а не створює нову Activity. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAlertIntent(intent)
    }

    /** «Деталі» у сповіщенні: відкрити ПЗ 18 і показати картку вибраної тривоги. */
    private fun handleAlertIntent(intent: Intent?) {
        if (intent?.action != AlertIntents.ACTION_OPEN) return
        val id = intent.getIntExtra(AlertIntents.EXTRA_ID, -1)
        AlertRepository.shared.focus(id.takeIf { it >= 0 })
        openRequest = PracticeWork.PW18
    }
}
