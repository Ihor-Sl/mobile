package com.example.myapplication.pw11.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.pw11.auth.Role
import com.example.myapplication.pw11.data.Session
import com.example.myapplication.pw11.data.remote.AtsTestDto
import com.example.myapplication.pw11.data.remote.AuditEntryDto
import com.example.myapplication.pw11.viewmodel.LoginForm
import com.example.myapplication.pw11.viewmodel.ProtectedData
import com.example.myapplication.pw11.viewmodel.SecurityCheck
import com.example.myapplication.ui.common.InfoRow
import com.example.myapplication.ui.common.MessageBanner
import com.example.myapplication.ui.common.SectionCard
import com.example.myapplication.ui.common.StatusBadge
import com.example.myapplication.ui.common.Tone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

fun Role.tone() = when (this) {
    Role.ADMIN -> Tone.BAD
    Role.OPERATOR -> Tone.WARN
    Role.VIEWER -> Tone.GOOD
}

/** Форма входу: логін, пароль із перемикачем видимості, вибір короткого терміну дії токена. */
@Composable
fun LoginCard(
    form: LoginForm,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onShortToken: (Boolean) -> Unit,
    onSubmit: () -> Unit,
) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    SectionCard(title = "Вхід") {
        OutlinedTextField(
            value = form.username, onValueChange = onUsername, label = { Text("Логін") },
            isError = form.usernameError != null, supportingText = form.usernameError?.let { { Text(it) } },
            singleLine = true, enabled = !form.submitting,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = form.password, onValueChange = onPassword, label = { Text("Пароль") },
            isError = form.passwordError != null, supportingText = form.passwordError?.let { { Text(it) } },
            singleLine = true, enabled = !form.submitting,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Сховати" else "Показати") } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = form.shortToken, onCheckedChange = onShortToken, enabled = !form.submitting)
            Text("Короткий термін дії токена (20 с) — для перевірки виходу", style = MaterialTheme.typography.bodySmall)
        }
        form.error?.let { MessageBanner(it, Tone.BAD) }
        Button(onClick = onSubmit, enabled = !form.submitting, modifier = Modifier.fillMaxWidth()) {
            if (form.submitting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Text("  Перевірка…")
            } else Text("Увійти")
        }
    }
}

/** Тестові (mocked) користувачі з різними ролями. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DemoAccountsCard(onPick: (String, String) -> Unit) {
    SectionCard(title = "Тестові облікові записи") {
        listOf(
            Triple("admin", "admin123", Role.ADMIN),
            Triple("operator", "oper123", Role.OPERATOR),
            Triple("viewer", "view123", Role.VIEWER),
        ).forEach { (u, p, role) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$u / $p", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                AssistChip(onClick = { onPick(u, p) }, label = { Text(role.label) })
            }
        }
        Text("3 невдалі спроби поспіль блокують вхід на 30 с", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Поточна сесія: користувач, роль, payload токена та зворотний відлік терміну дії. */
@Composable
fun SessionCard(session: Session, secondsLeft: Long, onLogout: () -> Unit) {
    val c = session.claims
    val total = (c.expiresAt - c.issuedAt).coerceAtLeast(1)
    val role = c.roleOrNull
    SectionCard(title = "Сесія") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Логін: ${c.subject}", style = MaterialTheme.typography.bodySmall)
            }
            role?.let { StatusBadge(it.label, it.tone()) }
        }
        val tone = if (secondsLeft <= 10) Tone.BAD else Tone.NEUTRAL
        InfoRow("Токен дійсний ще", "%d:%02d".format(secondsLeft / 60, secondsLeft % 60), valueColor = if (tone == Tone.BAD) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        LinearProgressIndicator(progress = { (secondsLeft.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp))
        Text(
            "payload: {sub: ${c.subject}, role: ${c.role}, iat: ${timeFormat.format(Date(c.issuedAt * 1000))}, exp: ${timeFormat.format(Date(c.expiresAt * 1000))}}",
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Bearer ${session.token.take(28)}…${session.token.takeLast(10)}",
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("Вийти") }
    }
}

/** Захищений ресурс, доступний усім ролям. */
@Composable
fun ReserveStatusCard(data: ProtectedData, onRefresh: () -> Unit) {
    SectionCard(title = "Стан резерву (GET /reserve/status)") {
        data.error?.let { MessageBanner(it, Tone.BAD) }
        val status = data.status
        if (status == null && data.loading) CircularProgressIndicator(Modifier.size(24.dp))
        status?.let { s ->
            InfoRow("Зовнішня мережа", if (s.gridOnline == true) "є" else "відсутня — працює резерв")
            s.units.orEmpty().forEach { u ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(u.name.orEmpty(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(u.state.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    }
                    val p = u.fuelPercent ?: 0
                    StatusBadge("$p %", if (p >= 50) Tone.GOOD else if (p >= 25) Tone.WARN else Tone.BAD)
                }
            }
        }
        TextButton(onClick = onRefresh) { Text("Оновити") }
    }
}

/** Секція лише для диспетчера та адміністратора. */
@Composable
fun AtsTestCard(result: AtsTestDto?, loading: Boolean, onRun: () -> Unit) {
    SectionCard(title = "Тест АВР (POST /ats/test) — диспетчер, адміністратор") {
        Button(onClick = onRun, enabled = !loading) { Text("Запустити тест автоматичного введення резерву") }
        result?.let {
            MessageBanner("${it.result}: перемикання за ${(it.transferMs ?: 0) / 1000.0} с, запустив(ла) ${it.startedBy}", Tone.GOOD)
        }
    }
}

/** Секція лише для адміністратора: журнал входів і відмов у доступі. */
@Composable
fun AuditCard(entries: List<AuditEntryDto>) {
    SectionCard(title = "Журнал доступу (GET /admin/audit) — лише адміністратор") {
        if (entries.isEmpty()) Text("Записів немає", style = MaterialTheme.typography.bodySmall)
        entries.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(timeFormat.format(Date(e.time ?: 0)), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("  ${e.user}: ${e.action}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                StatusBadge(if (e.success == true) "OK" else "відмова", if (e.success == true) Tone.GOOD else Tone.BAD)
            }
        }
    }
}

/** Перевірка захисту: сервер має відхилити кожен із цих запитів (401 / 403). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecurityChecksCard(
    checks: List<SecurityCheck>,
    thirdLabel: String,
    onWithoutToken: () -> Unit,
    onForged: () -> Unit,
    onForbidden: () -> Unit,
) {
    SectionCard(title = "Перевірка захисту захищених ресурсів") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onWithoutToken) { Text("Без токена") }
            OutlinedButton(onClick = onForged) { Text("Підроблений токен") }
            OutlinedButton(onClick = onForbidden) { Text(thirdLabel) }
        }
        checks.forEach { c ->
            MessageBanner("${c.title}\n${c.result}", if (c.passed) Tone.GOOD else Tone.BAD)
        }
    }
}
