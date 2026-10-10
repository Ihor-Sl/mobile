package com.example.myapplication.pw6.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.myapplication.pw6.data.RegisteredSource
import com.example.myapplication.pw6.data.SourceFormValidator
import com.example.myapplication.pw6.data.SourceType

/** Заголовок форми: назва та короткий опис. */
@Composable
fun FormHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Стан перевірки поля, що виконується асинхронно (унікальність інвентарного номера). */
enum class AsyncCheck { IDLE, CHECKING, OK, FAILED }

/**
 * Текстове поле з валідацією.
 *  - якщо є помилка — червона рамка (isError) і текст помилки під полем;
 *  - якщо помилки немає — підказка (helper text);
 *  - праворуч позначка стану: ✓ для коректного значення або індикатор асинхронної перевірки.
 */
@Composable
fun ValidatedField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    hint: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    valid: Boolean = false,
    check: AsyncCheck = AsyncCheck.IDLE,
    suffix: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    isSecret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        isError = error != null,
        supportingText = { Text(error ?: hint) },
        suffix = suffix?.let { { Text(it) } },
        trailingIcon = {
            when {
                check == AsyncCheck.CHECKING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                error == null && valid -> Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        },
        enabled = enabled,
        singleLine = true,
        visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
    )
}

/** Вибір типу джерела; зміна типу перебудовує поле «ємність» і правило інвентарного номера. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceTypeSelector(
    selected: SourceType,
    onSelected: (SourceType) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val types = SourceType.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        types.forEachIndexed { index, type ->
            SegmentedButton(
                selected = type == selected,
                onClick = { onSelected(type) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, types.size),
                label = { Text(if (type == SourceType.GENERATOR) "Дизель-генератор (ДГУ)" else "ДБЖ з АКБ") }
            )
        }
    }
}

/** Прогрес заповнення форми: смуга плавно змінюється при кожному коректно заповненому полі. */
@Composable
fun FormProgress(validCount: Int, total: Int, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState(validCount.toFloat() / total, label = "formProgress")
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Коректно заповнено: $validCount з $total полів",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

/** Повідомлення про помилку форми чи сервера; з'являється та зникає анімовано. */
@Composable
fun FormErrorBanner(message: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = message != null, modifier = modifier) {
        Text(
            text = message.orEmpty(),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/** Кнопка відправки зі станом завантаження: під час запиту заблокована та показує індикатор. */
@Composable
fun SubmitButton(text: String, loading: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, enabled = !loading, modifier = modifier.fillMaxWidth()) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            Spacer(Modifier.width(8.dp))
            Text("Реєстрація…")
        } else {
            Text(text)
        }
    }
}

/** Результат успішної реєстрації з розрахованою автономністю. */
@Composable
fun RegistrationResultCard(source: RegisteredSource, onRegisterAnother: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Джерело ${source.inventory} зареєстровано",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text("Тип: ${source.type.title}, ${SourceFormValidator.format(source.powerKw)} кВт")
            Text("${source.type.capacityLabel}: ${SourceFormValidator.format(source.capacity)} ${source.type.capacityUnit}")
            Text("Критичне навантаження: ${SourceFormValidator.format(source.criticalLoadKw)} кВт")
            Text("Відповідальний: ${source.engineerEmail}, ТО ${source.serviceDate}")
            Text(
                "Орієнтовна автономність: %.1f год".format(source.autonomyHours),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            OutlinedButton(onClick = onRegisterAnother, modifier = Modifier.padding(top = 8.dp)) {
                Text("Зареєструвати ще одне джерело")
            }
        }
    }
}
