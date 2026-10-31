package com.example.myapplication.pw9.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.myapplication.pw9.domain.TankDraft
import com.example.myapplication.pw9.domain.TankField

/**
 * Діалог додавання / редагування резервуара.
 * [onSave] повертає помилки валідації від ViewModel; порожня мапа означає успіх і закриття діалогу.
 */
@Composable
fun TankEditorDialog(
    initial: TankDraft,
    onDismiss: () -> Unit,
    onSave: (TankDraft) -> Map<TankField, String>,
) {
    var draft by remember { mutableStateOf(initial) }
    var errors by remember { mutableStateOf<Map<TankField, String>>(emptyMap()) }

    @Composable
    fun field(f: TankField, label: String, value: String, numeric: Boolean, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it); errors = errors - f },
            label = { Text(label) },
            isError = f in errors,
            supportingText = errors[f]?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
            modifier = modifier.fillMaxWidth()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == null) "Новий резервуар" else "Редагування ${initial.id}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                field(TankField.NAME, "Назва ДГУ", draft.name, false) { draft = draft.copy(name = it) }
                field(TankField.FACILITY, "Об'єкт", draft.facility, false) { draft = draft.copy(facility = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    field(TankField.CAPACITY, "Бак, л", draft.capacity, true, Modifier.weight(1f)) { draft = draft.copy(capacity = it) }
                    field(TankField.LEVEL, "Рівень, л", draft.level, true, Modifier.weight(1f)) { draft = draft.copy(level = it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    field(TankField.RATED, "Потужність, кВт", draft.rated, true, Modifier.weight(1f)) { draft = draft.copy(rated = it) }
                    field(TankField.LOAD, "Навантаж., кВт", draft.load, true, Modifier.weight(1f)) { draft = draft.copy(load = it) }
                }
                field(TankField.CONSUMPTION, "Витрата при 100 %, л/год", draft.consumption, true) { draft = draft.copy(consumption = it) }
            }
        },
        confirmButton = {
            Button(onClick = {
                errors = onSave(draft)
                if (errors.isEmpty()) onDismiss()
            }) { Text("Зберегти") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } }
    )
}
