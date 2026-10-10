package com.example.myapplication.pw8.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import com.example.myapplication.pw8.data.local.EventType
import com.example.myapplication.pw8.data.local.ReserveSourceEntity
import com.example.myapplication.pw8.domain.EventDraft
import com.example.myapplication.pw8.domain.EventField

/**
 * Діалог додавання / редагування події.
 * [onSave] повертає помилки валідації: якщо їх немає — подія збережена і діалог закривається.
 */
@Composable
fun EventEditorDialog(
    initial: EventDraft,
    sources: List<ReserveSourceEntity>,
    onSave: (EventDraft) -> Map<EventField, String>,
    onDismiss: () -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var errors by remember(initial) { mutableStateOf(emptyMap<EventField, String>()) }

    // Помилка поля зникає, щойно користувач починає його редагувати
    fun change(field: EventField, transform: EventDraft.() -> EventDraft) {
        draft = draft.transform()
        errors = errors - field
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Нова подія" else "Редагування події") },
        confirmButton = {
            TextButton(onClick = {
                errors = onSave(draft)
                if (errors.isEmpty()) onDismiss()
            }) { Text("Зберегти") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Джерело", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sources.forEach { source ->
                        FilterChip(
                            selected = draft.sourceId == source.id,
                            onClick = { change(EventField.SOURCE) { copy(sourceId = source.id) } },
                            label = { Text(source.name) }
                        )
                    }
                }
                errors[EventField.SOURCE]?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Text("Тип події", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EventType.entries.forEach { type ->
                        FilterChip(
                            selected = draft.type == type,
                            onClick = { draft = draft.copy(type = type) },
                            label = { Text(type.title) }
                        )
                    }
                }

                DraftField("Початок (дд.мм.рррр гг:хх)", draft.dateTime, errors[EventField.DATE]) {
                    change(EventField.DATE) { copy(dateTime = it) }
                }
                DraftField("Тривалість, хв", draft.durationMin, errors[EventField.DURATION], KeyboardType.Number) {
                    change(EventField.DURATION) { copy(durationMin = it) }
                }
                DraftField("Середнє навантаження, кВт", draft.avgLoadKw, errors[EventField.LOAD], KeyboardType.Decimal) {
                    change(EventField.LOAD) { copy(avgLoadKw = it) }
                }
                DraftField("Витрата палива, л", draft.fuelUsedL, errors[EventField.FUEL], KeyboardType.Decimal) {
                    change(EventField.FUEL) { copy(fuelUsedL = it) }
                }
                DraftField("Примітка", draft.note, errors[EventField.NOTE]) {
                    change(EventField.NOTE) { copy(note = it) }
                }
            }
        }
    )
}

/** Поле форми події з текстом помилки під ним. */
@Composable
private fun DraftField(
    label: String,
    value: String,
    error: String?,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth()
    )
}
