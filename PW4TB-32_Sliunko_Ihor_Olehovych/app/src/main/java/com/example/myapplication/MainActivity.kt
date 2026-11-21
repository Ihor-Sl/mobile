package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.myapplication.host.PracticeHost
import com.example.myapplication.ui.theme.MyApplicationTheme

/**
 * Точка входу застосунку «Система аварійного електропостачання» (варіант 18).
 *
 * Практична робота №4 (Flow та Coroutines, LazyColumn і мапи, фільтрація та сортування,
 * GPS і мапи) складається з чотирьох занять; кожне заняття виконано на просунутому рівні
 * та винесено в окремий пакет (pw13 … pw16).
 * Перемикання між заняттями виконує [PracticeHost].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                PracticeHost()
            }
        }
    }
}
