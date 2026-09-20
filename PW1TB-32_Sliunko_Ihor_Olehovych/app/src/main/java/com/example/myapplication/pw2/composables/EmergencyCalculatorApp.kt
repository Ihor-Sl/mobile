package com.example.myapplication.pw2.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.myapplication.pw2.data.EmergencyPowerCalculator
import com.example.myapplication.pw2.data.EmergencyPowerResult
import com.example.myapplication.pw2.data.InputField
import com.example.myapplication.pw2.data.ParseResult

/**
 * ПЗ 2, просунутий рівень: розрахунок автономності системи аварійного електропостачання.
 *
 * Розділення відповідальності:
 *  - UI (цей файл) лише зберігає стан та відображає результат;
 *  - обчислення й валідація — у [EmergencyPowerCalculator] (пакет data), без залежностей від Compose;
 *  - графік — окремий composable [RuntimeProfileChart], намальований нативно на Compose Canvas.
 *
 * UI-State (усе переживає поворот екрана завдяки rememberSaveable):
 *  - [raw] — текст усіх полів форми;
 *  - [applied] — «знімок» форми на момент натискання «Розрахувати» (null, доки розрахунок не виконано).
 * Результат та помилки — похідні від цього стану й обчислюються через remember(...).
 */
@Composable
fun EmergencyCalculatorApp(modifier: Modifier = Modifier) {
    var raw by rememberSaveable { mutableStateOf(InputField.defaults()) }
    var applied by rememberSaveable { mutableStateOf<Map<InputField, String>?>(null) }

    // Результат розбору форми на момент натискання кнопки
    val parsed = remember(applied) { applied?.let(EmergencyPowerCalculator::parse) }
    val errors = (parsed as? ParseResult.Invalid)?.errors.orEmpty()
    val result = remember(parsed) {
        (parsed as? ParseResult.Valid)?.let { EmergencyPowerCalculator.calculate(it.input) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Розрахунок автономності",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Сценарій аварії мережі: ДГУ працює, доки є паливо, потім навантаження бере на себе АКБ " +
                "(резерв ${EmergencyPowerCalculator.BATTERY_RESERVE_PERCENT.toInt()} %).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ParametersForm(
            values = raw,
            errors = errors,
            onValueChange = { field, text -> raw = raw + (field to text) },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { applied = raw }, modifier = Modifier.weight(1f)) {
                Text("Розрахувати")
            }
            OutlinedButton(
                onClick = {
                    raw = InputField.defaults()
                    applied = null
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Скинути")
            }
        }

        if (result != null) {
            ResultSection(result)
        }
    }
}

/**
 * Форма параметрів: поля відображаються по два в рядку.
 * Набір полів береться з [InputField.entries], тому нові параметри з'являються автоматично.
 */
@Composable
private fun ParametersForm(
    values: Map<InputField, String>,
    errors: Map<InputField, String>,
    onValueChange: (InputField, String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Параметри системи", style = MaterialTheme.typography.titleMedium)
            InputField.entries.chunked(2).forEach { rowFields ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowFields.forEach { field ->
                        ParameterTextField(
                            field = field,
                            value = values[field].orEmpty(),
                            error = errors[field],
                            onValueChange = { onValueChange(field, it) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // Вирівнюємо останній рядок, якщо полів непарна кількість
                    if (rowFields.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Числове поле з одиницею виміру та повідомленням про помилку валідації. */
@Composable
private fun ParameterTextField(
    field: InputField,
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(field.label, maxLines = 1) },
        suffix = { Text(field.unit) },
        isError = error != null,
        supportingText = { Text(error ?: " ") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

/** Блок результатів: підсумок, деталізація та графік профілю. */
@Composable
private fun ResultSection(result: EmergencyPowerResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Загальна автономність", style = MaterialTheme.typography.labelLarge)
            Text(
                text = EmergencyPowerCalculator.formatDuration(result.totalHours),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ResultRow("Робота ДГУ", EmergencyPowerCalculator.formatDuration(result.generatorPhaseHours))
            ResultRow("Потім лише АКБ", EmergencyPowerCalculator.formatDuration(result.batteryPhaseHours))
            ResultRow("Корисна енергія АКБ", "%.1f кВт·год".format(result.usableEnergyKwh))
            ResultRow("Завантаження ДГУ", "%.0f %%".format(result.generatorLoadPercent))
            ResultRow("Витрата палива", "%.1f л/год".format(result.fuelRateLph))
            if (result.generatorOverloaded) {
                Text(
                    text = "Увага: навантаження перевищує потужність ДГУ — різницю покриває АКБ.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Профіль ресурсів", style = MaterialTheme.typography.titleMedium)
            RuntimeProfileChart(points = result.profile)
        }
    }
}

/** Рядок «назва — значення». */
@Composable
private fun ResultRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
