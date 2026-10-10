package com.example.myapplication.pw5.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication.pw5.components.ScreenHeader
import com.example.myapplication.pw5.components.SectionCard
import com.example.myapplication.pw5.data.OperatorSession
import com.example.myapplication.pw5.data.Shift

/**
 * Профіль оператора та налаштування розрахунку.
 *
 * Поточні значення приходять аргументами маршруту ([initial]), а при збереженні новий стан
 * віддається через [onSave] — NavHost записує його в savedStateHandle головного меню і повертається назад.
 */
@Composable
fun OperatorProfileScreen(
    initial: OperatorSession,
    onSave: (OperatorSession) -> Unit,
    onBack: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var shift by rememberSaveable { mutableStateOf(initial.shift) }
    var criticalOnly by rememberSaveable { mutableStateOf(initial.criticalOnly) }
    var showErrors by rememberSaveable { mutableStateOf(false) }

    val nameError = when {
        name.isBlank() -> "Введіть ім'я оператора"
        name.trim().length < 2 -> "Щонайменше 2 символи"
        else -> null
    }

    AdaptiveScreen {
        ScreenHeader(title = "Профіль оператора", subtitle = "Дані зміни та режим розрахунку", onBack = onBack)

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Ім'я оператора") },
            singleLine = true,
            isError = showErrors && nameError != null,
            supportingText = { Text(if (showErrors && nameError != null) nameError else "Відображається в головному меню") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )

        Text("Зміна", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Shift.entries.forEach { item ->
                FilterChip(
                    selected = item == shift,
                    onClick = { shift = item },
                    label = { Text(item.title) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        SectionCard(title = "Налаштування розрахунку") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Лише критичні споживачі", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Режим скидання навантаження: автономність рахується для відповідальних споживачів",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = criticalOnly, onCheckedChange = { criticalOnly = it })
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Скасувати") }
            Button(
                onClick = {
                    showErrors = true
                    if (nameError == null) onSave(OperatorSession(name.trim(), shift, criticalOnly))
                },
                modifier = Modifier.weight(1f)
            ) { Text("Зберегти") }
        }
    }
}
