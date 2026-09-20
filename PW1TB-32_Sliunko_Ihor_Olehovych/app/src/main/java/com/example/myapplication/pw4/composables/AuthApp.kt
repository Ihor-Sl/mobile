package com.example.myapplication.pw4.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.myapplication.pw4.data.Account
import com.example.myapplication.pw4.data.AuthResult
import com.example.myapplication.pw4.data.AuthValidator
import com.example.myapplication.pw4.data.FakeAuthRepository
import com.example.myapplication.pw4.data.OperatorRole
import kotlinx.coroutines.launch

/** Режим екрана: вхід або реєстрація. */
private enum class AuthMode { LOGIN, REGISTER }

/** Максимальна ширина форми: на планшетах вона не розтягується на весь екран. */
private val FORM_MAX_WIDTH = 480.dp

/**
 * ПЗ 4, просунутий рівень: авторизація та реєстрація оператора системи аварійного електропостачання.
 *
 * Структура: цей composable керує лише режимом і сесією, а форми, поля та кнопки винесені
 * в окремі composable-функції (AuthComponents.kt). Перевірка даних — у AuthValidator,
 * «запити до сервера» — у FakeAuthRepository.
 *
 * UI-State:
 *  - [mode] — вхід / реєстрація (rememberSaveable);
 *  - [session] — акаунт після успішного входу (null — не авторизований);
 *  - [notice] та [prefillEmail] — повідомлення й e-mail, які передаються з реєстрації у форму входу.
 *
 * Адаптивність: форма центрується й обмежена по ширині, а весь екран прокручується —
 * тому вона коректно виглядає і на телефоні, і на планшеті, і в ландшафті з клавіатурою.
 */
@Composable
fun AuthApp(modifier: Modifier = Modifier) {
    var mode by rememberSaveable { mutableStateOf(AuthMode.LOGIN) }
    var session by remember { mutableStateOf<Account?>(null) }
    var notice by remember { mutableStateOf<FormMessage?>(null) }
    var prefillEmail by rememberSaveable { mutableStateOf("") }

    Box(
        modifier = modifier
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
            val account = session
            if (account != null) {
                SignedInPanel(account, onSignOut = { session = null; notice = null })
            } else {
                when (mode) {
                    AuthMode.LOGIN -> LoginForm(
                        initialEmail = prefillEmail,
                        notice = notice,
                        onAuthenticated = { session = it },
                    )
                    AuthMode.REGISTER -> RegistrationForm(
                        onRegistered = { created ->
                            prefillEmail = created.email
                            notice = FormMessage("Акаунт створено. Тепер увійдіть із вашим паролем.", isError = false)
                            mode = AuthMode.LOGIN
                        },
                    )
                }
                TextButton(
                    onClick = {
                        notice = null
                        mode = if (mode == AuthMode.LOGIN) AuthMode.REGISTER else AuthMode.LOGIN
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(if (mode == AuthMode.LOGIN) "Немає акаунта? Зареєструватися" else "Вже є акаунт? Увійти")
                }
            }
        }
    }
}

/**
 * Форма входу: e-mail + пароль.
 * UI-State: значення полів (rememberSaveable), помилки полів, індикатор завантаження та повідомлення (remember).
 */
@Composable
private fun LoginForm(
    initialEmail: String,
    notice: FormMessage?,
    onAuthenticated: (Account) -> Unit,
) {
    var email by rememberSaveable(initialEmail) { mutableStateOf(initialEmail) }
    var password by rememberSaveable { mutableStateOf("") }
    var emailError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember(notice) { mutableStateOf(notice) }

    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Перевірка даних і, якщо вони коректні, запит до «сервера»
    fun submit() {
        emailError = AuthValidator.validateEmail(email)
        passwordError = AuthValidator.validateLoginPassword(password)
        if (emailError != null || passwordError != null) {
            message = FormMessage("Виправте помилки у формі", isError = true)
            return
        }
        focusManager.clearFocus()
        message = null
        loading = true
        scope.launch {
            when (val result = FakeAuthRepository.login(email, password)) {
                is AuthResult.Success -> onAuthenticated(result.account)
                is AuthResult.Failure -> message = FormMessage(result.message, isError = true)
            }
            loading = false
        }
    }

    AuthHeader(
        title = "Вхід у систему",
        subtitle = "Диспетчерська аварійного електропостачання"
    )
    AuthTextField(
        label = "E-mail",
        value = email,
        onValueChange = { email = it; emailError = null },
        error = emailError,
        enabled = !loading,
        keyboardType = KeyboardType.Email,
        modifier = Modifier.padding(top = 8.dp)
    )
    PasswordField(
        label = "Пароль",
        value = password,
        onValueChange = { password = it; passwordError = null },
        error = passwordError,
        enabled = !loading,
        imeAction = ImeAction.Done,
        onImeAction = ::submit,
    )
    FormMessageBanner(message)
    SubmitButton(text = "Увійти", loading = loading, onClick = ::submit)
    Text(
        text = "Демо-акаунт: ${FakeAuthRepository.DEMO_EMAIL} / ${FakeAuthRepository.DEMO_PASSWORD}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Форма реєстрації: ПІБ, e-mail, роль, пароль із підтвердженням та згода з регламентом.
 * Перевірка виконується при натисканні кнопки; помилка конкретного поля зникає, щойно користувач його редагує.
 */
@Composable
private fun RegistrationForm(onRegistered: (Account) -> Unit) {
    var fullName by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(OperatorRole.DISPATCHER) }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var termsAccepted by rememberSaveable { mutableStateOf(false) }

    var nameError by remember { mutableStateOf<String?>(null) }
    var emailError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var confirmError by remember { mutableStateOf<String?>(null) }
    var termsError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<FormMessage?>(null) }

    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    fun submit() {
        nameError = AuthValidator.validateFullName(fullName)
        emailError = AuthValidator.validateEmail(email)
        passwordError = AuthValidator.validateNewPassword(password)
        confirmError = AuthValidator.validateConfirmPassword(password, confirm)
        termsError = AuthValidator.validateTermsAccepted(termsAccepted)
        val hasErrors = listOf(nameError, emailError, passwordError, confirmError, termsError).any { it != null }
        if (hasErrors) {
            message = FormMessage("Виправте помилки у формі", isError = true)
            return
        }
        focusManager.clearFocus()
        message = null
        loading = true
        scope.launch {
            when (val result = FakeAuthRepository.register(fullName, email, role, password)) {
                is AuthResult.Success -> onRegistered(result.account)
                is AuthResult.Failure -> message = FormMessage(result.message, isError = true)
            }
            loading = false
        }
    }

    AuthHeader(
        title = "Реєстрація оператора",
        subtitle = "Створіть обліковий запис для доступу до системи"
    )
    AuthTextField(
        label = "Прізвище та ім'я",
        value = fullName,
        onValueChange = { fullName = it; nameError = null },
        error = nameError,
        enabled = !loading,
        modifier = Modifier.padding(top = 8.dp)
    )
    AuthTextField(
        label = "E-mail",
        value = email,
        onValueChange = { email = it; emailError = null },
        error = emailError,
        enabled = !loading,
        keyboardType = KeyboardType.Email,
    )
    RoleSelector(selected = role, onSelected = { role = it }, enabled = !loading)
    PasswordField(
        label = "Пароль",
        value = password,
        onValueChange = { password = it; passwordError = null },
        error = passwordError,
        enabled = !loading,
        modifier = Modifier.padding(top = 8.dp)
    )
    PasswordStrengthIndicator(
        strength = if (password.isEmpty()) null else AuthValidator.passwordStrength(password)
    )
    PasswordField(
        label = "Підтвердження пароля",
        value = confirm,
        onValueChange = { confirm = it; confirmError = null },
        error = confirmError,
        enabled = !loading,
        imeAction = ImeAction.Done,
        onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
        modifier = Modifier.padding(top = 8.dp)
    )
    TermsCheckbox(
        checked = termsAccepted,
        onCheckedChange = { termsAccepted = it; termsError = null },
        error = termsError,
        enabled = !loading,
    )
    FormMessageBanner(message)
    SubmitButton(text = "Зареєструватися", loading = loading, onClick = ::submit)
}

/** Екран після успішного входу: дані акаунта та кнопка виходу. */
@Composable
private fun SignedInPanel(account: Account, onSignOut: () -> Unit) {
    AuthHeader(
        title = "Вітаємо, ${account.fullName}",
        subtitle = "Ви успішно авторизовані в системі"
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Роль: ${account.role.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("E-mail: ${account.email}", style = MaterialTheme.typography.bodyMedium)
        }
    }
    OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
        Text("Вийти")
    }
}
