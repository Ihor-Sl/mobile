package com.example.myapplication.pw12.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.pw12.ui.components.ErrorDialog
import com.example.myapplication.pw12.ui.components.ErrorLogCard
import com.example.myapplication.pw12.ui.components.RequestForm
import com.example.myapplication.pw12.ui.components.RequestStateCard
import com.example.myapplication.pw12.ui.components.ScenarioPicker
import com.example.myapplication.pw12.viewmodel.AutonomyViewModel
import com.example.myapplication.pw12.viewmodel.RequestState
import com.example.myapplication.ui.common.MessageBanner
import com.example.myapplication.ui.common.ScreenTitle
import com.example.myapplication.ui.common.Tone

private val WIDE_SCREEN = 720.dp

/**
 * ПЗ 12, просунутий рівень: обробка помилок мережевих запитів.
 *
 * UI лише відображає стан з ViewModel:
 *  - банер «Немає з'єднання» — з потоку NetworkMonitor (оновлюється без запиту);
 *  - тимчасові помилки під час повторів — Snackbar і картка зі зворотним відліком;
 *  - остаточна помилка — AlertDialog з кнопкою «Повторити»;
 *  - журнал — останні рядки файлу помилок.
 */
@Composable
fun AutonomyScreen(viewModel: AutonomyViewModel = viewModel(factory = AutonomyViewModel.Factory)) {
    val input by viewModel.input.collectAsStateWithLifecycle()
    val request by viewModel.request.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val scenario by viewModel.scenario.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        viewModel.noticeShown()
        snackbar.showSnackbar(n.text)
    }

    val busy = request is RequestState.Loading
    val left: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenTitle("Прогноз автономності", "Перевірка мережі, retry, HTTP-коди та журнал помилок")
            if (!online) MessageBanner("Відсутнє з'єднання з інтернетом — запити не надсилаються", Tone.BAD)
            ScenarioPicker(scenario, viewModel::setScenario)
            RequestForm(input, busy, viewModel::onCodeChange, viewModel::onHoursChange, viewModel::requestForecast)
            RequestStateCard(request)
        }
    }
    val right: @Composable () -> Unit = { ErrorLogCard(log, viewModel::clearLog) }

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= WIDE_SCREEN) {
                Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { left() }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { right() }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    left(); right()
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    (request as? RequestState.Failed)?.takeIf { it.showDialog }?.let { failed ->
        ErrorDialog(failed.error, failed.attempts, onRetry = viewModel::requestForecast, onDismiss = viewModel::dismissError)
    }
}
