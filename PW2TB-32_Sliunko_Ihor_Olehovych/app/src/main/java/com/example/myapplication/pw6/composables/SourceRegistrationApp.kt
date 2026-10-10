package com.example.myapplication.pw6.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.myapplication.pw6.components.AsyncCheck
import com.example.myapplication.pw6.components.FormErrorBanner
import com.example.myapplication.pw6.components.FormHeader
import com.example.myapplication.pw6.components.FormProgress
import com.example.myapplication.pw6.components.RegistrationResultCard
import com.example.myapplication.pw6.components.SourceTypeSelector
import com.example.myapplication.pw6.components.SubmitButton
import com.example.myapplication.pw6.components.ValidatedField
import com.example.myapplication.pw6.data.RegisteredSource
import com.example.myapplication.pw6.data.RegistrationResult
import com.example.myapplication.pw6.data.SimpleDate
import com.example.myapplication.pw6.data.SourceField
import com.example.myapplication.pw6.data.SourceForm
import com.example.myapplication.pw6.data.SourceFormValidator
import com.example.myapplication.pw6.data.SourceRegistry
import java.util.Calendar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Затримка перед перевіркою унікальності: запит не надсилається на кожен введений символ. */
private const val DEBOUNCE_MS = 400L

/** Ширина, з якої поля форми розташовуються у дві колонки. */
private val WIDE_SCREEN = 600.dp
private val FORM_MAX_WIDTH = 900.dp

/** Сьогоднішня дата для перевірки дати ТО. */
private fun today(): SimpleDate = Calendar.getInstance().let {
    SimpleDate(it.get(Calendar.DAY_OF_MONTH), it.get(Calendar.MONTH) + 1, it.get(Calendar.YEAR))
}

/**
 * ПЗ 6, просунутий рівень: форма реєстрації резервного джерела (ДГУ / ДБЖ) з комплексною валідацією.
 *
 * UI-State:
 *  - [form] — усі значення форми (rememberSaveable, переживає поворот екрана);
 *  - [touched] — поля, які користувач уже редагував: помилка показується лише для них
 *    (або для всіх після першої спроби відправки — [submitAttempted]);
 *  - [inventoryCheck] — асинхронна перевірка унікальності інвентарного номера;
 *  - [loading], [formError], [registered] — стан відправки форми.
 *
 * Помилки обчислюються функцією SourceFormValidator.validate при кожній зміні форми,
 * тому UI оновлюється динамічно під час введення.
 */
@Composable
fun SourceRegistrationApp() {
    var form by rememberSaveable { mutableStateOf(SourceForm()) }
    var touched by rememberSaveable { mutableStateOf(emptySet<SourceField>()) }
    var submitAttempted by rememberSaveable { mutableStateOf(false) }
    var inventoryCheck by remember { mutableStateOf(AsyncCheck.IDLE) }
    var loading by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var registered by remember { mutableStateOf<RegisteredSource?>(null) }

    val today = remember { today() }
    val errors = remember(form) { SourceFormValidator.validate(form, today) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Перевірка унікальності запускається, коли номер має правильний формат.
    // Зміна номера скасовує попередню корутину LaunchedEffect — так реалізовано debounce.
    val inventoryFormatOk = SourceField.INVENTORY !in errors
    LaunchedEffect(form.inventory, inventoryFormatOk) {
        if (!inventoryFormatOk) {
            inventoryCheck = AsyncCheck.IDLE
            return@LaunchedEffect
        }
        inventoryCheck = AsyncCheck.CHECKING
        delay(DEBOUNCE_MS)
        inventoryCheck = if (SourceRegistry.isInventoryTaken(form.inventory)) AsyncCheck.FAILED else AsyncCheck.OK
    }

    /** Помилка поля з урахуванням того, чи вже треба її показувати. */
    fun errorOf(field: SourceField): String? {
        if (field !in touched && !submitAttempted) return null
        if (field == SourceField.INVENTORY && inventoryCheck == AsyncCheck.FAILED && errors[field] == null) {
            return "Цей номер уже є в реєстрі"
        }
        return errors[field]
    }

    fun update(field: SourceField, transform: SourceForm.() -> SourceForm) {
        form = form.transform()
        touched = touched + field
        formError = null
    }

    fun submit() {
        submitAttempted = true
        if (errors.isNotEmpty() || inventoryCheck == AsyncCheck.FAILED) {
            formError = "Виправте помилки у формі (${errors.size + if (inventoryCheck == AsyncCheck.FAILED) 1 else 0})"
            return
        }
        focusManager.clearFocus()
        formError = null
        loading = true
        scope.launch {
            when (val result = SourceRegistry.register(form)) {
                is RegistrationResult.Success -> registered = result.source
                is RegistrationResult.Failure -> {
                    formError = result.message
                    inventoryCheck = AsyncCheck.FAILED
                }
            }
            loading = false
        }
    }

    fun reset() {
        form = SourceForm()
        touched = emptySet()
        submitAttempted = false
        registered = null
        formError = null
    }

    val validCount = SourceField.entries.count { it !in errors } -
        if (inventoryCheck == AsyncCheck.FAILED && SourceField.INVENTORY !in errors) 1 else 0

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = FORM_MAX_WIDTH)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FormHeader(
                title = "Реєстрація резервного джерела",
                subtitle = "Внесення ДГУ або ДБЖ до реєстру системи аварійного електропостачання"
            )

            val done = registered
            if (done != null) {
                RegistrationResultCard(done, onRegisterAnother = ::reset, modifier = Modifier.padding(top = 8.dp))
                return@Column
            }

            SourceTypeSelector(
                selected = form.type,
                onSelected = { type -> form = form.copy(type = type) },
                enabled = !loading,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            FormProgress(validCount = validCount, total = SourceField.entries.size)

            // Опис усіх полів форми; макет (одна чи дві колонки) обирається нижче
            val fields: List<@Composable (Modifier) -> Unit> = listOf(
                { m ->
                    ValidatedField(
                        label = "Інвентарний номер", value = form.inventory,
                        onValueChange = { v -> update(SourceField.INVENTORY) { copy(inventory = v.uppercase()) } },
                        error = errorOf(SourceField.INVENTORY),
                        hint = if (inventoryCheck == AsyncCheck.OK) "Номер вільний" else SourceFormValidator.hint(SourceField.INVENTORY, form),
                        enabled = !loading, modifier = m,
                        valid = inventoryCheck == AsyncCheck.OK, check = inventoryCheck,
                    )
                },
                { m ->
                    ValidatedField(
                        label = "Номінальна потужність", value = form.power,
                        onValueChange = { v -> update(SourceField.POWER) { copy(power = v) } },
                        error = errorOf(SourceField.POWER), hint = SourceFormValidator.hint(SourceField.POWER, form),
                        enabled = !loading, modifier = m, valid = SourceField.POWER !in errors,
                        suffix = "кВт", keyboardType = KeyboardType.Decimal,
                    )
                },
                { m ->
                    ValidatedField(
                        label = form.type.capacityLabel, value = form.capacity,
                        onValueChange = { v -> update(SourceField.CAPACITY) { copy(capacity = v) } },
                        error = errorOf(SourceField.CAPACITY), hint = SourceFormValidator.hint(SourceField.CAPACITY, form),
                        enabled = !loading, modifier = m, valid = SourceField.CAPACITY !in errors,
                        suffix = form.type.capacityUnit, keyboardType = KeyboardType.Decimal,
                    )
                },
                { m ->
                    ValidatedField(
                        label = "Навантаження критичних споживачів", value = form.criticalLoad,
                        onValueChange = { v -> update(SourceField.CRITICAL_LOAD) { copy(criticalLoad = v) } },
                        error = errorOf(SourceField.CRITICAL_LOAD), hint = SourceFormValidator.hint(SourceField.CRITICAL_LOAD, form),
                        enabled = !loading, modifier = m, valid = SourceField.CRITICAL_LOAD !in errors,
                        suffix = "кВт", keyboardType = KeyboardType.Decimal,
                    )
                },
                { m ->
                    ValidatedField(
                        label = "E-mail відповідального інженера", value = form.email,
                        onValueChange = { v -> update(SourceField.EMAIL) { copy(email = v) } },
                        error = errorOf(SourceField.EMAIL), hint = SourceFormValidator.hint(SourceField.EMAIL, form),
                        enabled = !loading, modifier = m, valid = SourceField.EMAIL !in errors,
                        keyboardType = KeyboardType.Email,
                    )
                },
                { m ->
                    ValidatedField(
                        label = "Дата останнього ТО", value = form.serviceDate,
                        onValueChange = { v -> update(SourceField.SERVICE_DATE) { copy(serviceDate = v) } },
                        error = errorOf(SourceField.SERVICE_DATE), hint = SourceFormValidator.hint(SourceField.SERVICE_DATE, form),
                        enabled = !loading, modifier = m, valid = SourceField.SERVICE_DATE !in errors,
                    )
                },
                { m ->
                    ValidatedField(
                        label = "PIN пульта керування", value = form.pin,
                        onValueChange = { v -> update(SourceField.PIN) { copy(pin = v.filter(Char::isDigit).take(SourceFormValidator.PIN_LENGTH)) } },
                        error = errorOf(SourceField.PIN), hint = SourceFormValidator.hint(SourceField.PIN, form),
                        enabled = !loading, modifier = m, valid = SourceField.PIN !in errors,
                        keyboardType = KeyboardType.NumberPassword, isSecret = true,
                    )
                },
            )

            // Адаптивний макет: на широкому екрані поля йдуть парами у два стовпці
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= WIDE_SCREEN) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        fields.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                pair.forEach { field -> field(Modifier.weight(1f)) }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        fields.forEach { field -> field(Modifier.fillMaxWidth()) }
                    }
                }
            }

            FormErrorBanner(formError)
            SubmitButton(text = "Зареєструвати джерело", loading = loading, onClick = ::submit)
        }
    }
}
