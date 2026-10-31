package com.example.myapplication.pw12.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.myapplication.pw12.data.AutonomyForecast
import com.example.myapplication.pw12.data.remote.FaultScenario
import com.example.myapplication.pw12.errors.AppError
import com.example.myapplication.pw12.viewmodel.InputState
import com.example.myapplication.pw12.viewmodel.RequestState
import com.example.myapplication.ui.common.InfoRow
import com.example.myapplication.ui.common.MessageBanner
import com.example.myapplication.ui.common.SectionCard
import com.example.myapplication.ui.common.StatusBadge
import com.example.myapplication.ui.common.Tone
import com.example.myapplication.ui.common.colors
import com.example.myapplication.ui.common.fmt

/** Вибір сценарію поведінки сервера (імітація збоїв). */
@Composable
fun ScenarioPicker(scenario: FaultScenario, onPick: (FaultScenario) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Сценарій сервера:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(scenario.label) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                FaultScenario.entries.forEach { s ->
                    DropdownMenuItem(text = { Text(s.label) }, onClick = { onPick(s); open = false })
                }
            }
        }
    }
}

/** Поля запиту з базовою перевіркою введення. */
@Composable
fun RequestForm(input: InputState, busy: Boolean, onCode: (String) -> Unit, onHours: (String) -> Unit, onSubmit: () -> Unit) {
    SectionCard(title = "Запит прогнозу автономності") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input.code, onValueChange = onCode, label = { Text("Код об'єкта") },
                isError = input.codeError != null, supportingText = { Text(input.codeError ?: "OBJ-01 … OBJ-05") },
                singleLine = true, enabled = !busy, modifier = Modifier.weight(1.2f)
            )
            OutlinedTextField(
                value = input.hours, onValueChange = onHours, label = { Text("Горизонт, год") },
                isError = input.hoursError != null, supportingText = { Text(input.hoursError ?: "1–72") },
                singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        Button(onClick = onSubmit, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Виконується…" else "Отримати прогноз")
        }
    }
}

/** Стан запиту: номер спроби, причина повтору, зворотний відлік; або результат. */
@Composable
fun RequestStateCard(state: RequestState) {
    when (state) {
        RequestState.Idle -> MessageBanner("Оберіть сценарій сервера та надішліть запит", Tone.NEUTRAL)
        is RequestState.Loading -> SectionCard(title = "Виконання запиту") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text(
                    if (state.retryInSec != null) "  Спроба ${state.attempt} з ${state.maxAttempts} невдала · повтор через ${state.retryInSec} с"
                    else "  Спроба ${state.attempt} з ${state.maxAttempts}…",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            state.lastError?.let { MessageBanner("${it.title}: ${it.userMessage}", Tone.WARN) }
        }
        is RequestState.Success -> ForecastCard(state.forecast, state.attempts)
        is RequestState.Failed -> MessageBanner("${state.error.title}: ${state.error.userMessage}", Tone.BAD)
    }
}

/** Результат: графік рівня палива по годинах і висновок. */
@Composable
fun ForecastCard(f: AutonomyForecast, attempts: Int) {
    val tone = if (f.enough) Tone.GOOD else Tone.BAD
    val line = tone.colors().second
    val grid = MaterialTheme.colorScheme.outlineVariant
    SectionCard(title = "Прогноз: ${f.name} (${f.code})") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (f.enough) "Резерву вистачить на ${f.horizonH} год" else "Паливо закінчиться через ${f.autonomyH.fmt()} год",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
            )
            StatusBadge(if (attempts > 1) "з $attempts-ї спроби" else "1 спроба", if (attempts > 1) Tone.WARN else Tone.GOOD)
        }
        val max = f.fuelByHour.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val n = (f.fuelByHour.size - 1).coerceAtLeast(1)
            listOf(0f, 0.5f, 1f).forEach { y ->
                drawLine(grid, Offset(0f, size.height * y), Offset(size.width, size.height * y), strokeWidth = 1f)
            }
            val path = Path()
            f.fuelByHour.forEachIndexed { i, v ->
                val p = Offset(size.width * i / n, size.height * (1 - (v / max).toFloat()))
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, line, style = Stroke(width = 5f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0 год · ${f.fuelByHour.first().fmt(0)} л", style = MaterialTheme.typography.labelSmall)
            Text("${f.horizonH} год · ${f.fuelByHour.last().fmt(0)} л", style = MaterialTheme.typography.labelSmall)
        }
        InfoRow("Автономність", "${f.autonomyH.fmt()} год")
        InfoRow("Витрата палива", "${f.consumptionLph.fmt(2)} л/год")
    }
}

/** Діалог для остаточних помилок; «Повторити» доступна для помилок, які варто повторити вручну. */
@Composable
fun ErrorDialog(error: AppError, attempts: Int, onRetry: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(error.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(error.userMessage)
                if (attempts > 1) Text("Виконано спроб: $attempts — помилка не зникла.", style = MaterialTheme.typography.bodySmall)
                if (error is AppError.Http && error.serverMessage != null) {
                    Text("Відповідь сервера: ${error.serverMessage}", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { Button(onClick = { onDismiss(); onRetry() }) { Text("Повторити") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрити") } }
    )
}

/** Останні записи журналу помилок (файл network_errors.log). */
@Composable
fun ErrorLogCard(lines: List<String>, onClear: () -> Unit) {
    SectionCard(title = "Журнал помилок (network_errors.log)") {
        if (lines.isEmpty()) Text("Помилок не зареєстровано", style = MaterialTheme.typography.bodySmall)
        lines.forEach { line ->
            Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
        }
        if (lines.isNotEmpty()) TextButton(onClick = onClear) { Text("Очистити журнал") }
    }
}
