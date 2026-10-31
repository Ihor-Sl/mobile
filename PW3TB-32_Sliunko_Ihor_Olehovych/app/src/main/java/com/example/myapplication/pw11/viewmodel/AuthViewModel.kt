package com.example.myapplication.pw11.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myapplication.pw11.auth.Role
import com.example.myapplication.pw11.data.AuthError
import com.example.myapplication.pw11.data.AuthRepository
import com.example.myapplication.pw11.data.Session
import com.example.myapplication.pw11.data.remote.AtsTestDto
import com.example.myapplication.pw11.data.remote.AuthApi
import com.example.myapplication.pw11.data.remote.AuditEntryDto
import com.example.myapplication.pw11.data.remote.ReserveStatusDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Поля форми входу та їх помилки. */
data class LoginForm(
    val username: String = "",
    val password: String = "",
    val shortToken: Boolean = false,
    val usernameError: String? = null,
    val passwordError: String? = null,
    val submitting: Boolean = false,
    val error: String? = null,
)

/** Стан автентифікації. */
sealed interface AuthState {
    /** Читання токена з DataStore при запуску. */
    data object Restoring : AuthState
    data class LoggedOut(val notice: String? = null) : AuthState
    data class LoggedIn(val session: Session, val secondsLeft: Long) : AuthState
}

/** Дані захищених ресурсів (видимість залежить від ролі). */
data class ProtectedData(
    val loading: Boolean = false,
    val status: ReserveStatusDto? = null,
    val atsResult: AtsTestDto? = null,
    val audit: List<AuditEntryDto> = emptyList(),
    val error: String? = null,
)

/** Результат демонстраційної перевірки захисту. */
data class SecurityCheck(val title: String, val result: String, val passed: Boolean)

/**
 * ViewModel ПЗ 11: керує сесією, токеном і доступом до захищених ресурсів.
 *
 * Розмежування доступу: роль береться з payload JWT ([Session.claims]) і визначає,
 * які секції UI показуються ([canRunAts], [canSeeAudit]). Це лише зручність для користувача —
 * справжній захист забезпечує сервер, який перевіряє підпис і роль у кожному запиті (403).
 */
class AuthViewModel(private val repository: AuthRepository, private val nowMs: () -> Long = System::currentTimeMillis) : ViewModel() {

    private val _state = MutableStateFlow<AuthState>(AuthState.Restoring)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _form = MutableStateFlow(LoginForm())
    val form: StateFlow<LoginForm> = _form.asStateFlow()

    private val _data = MutableStateFlow(ProtectedData())
    val data: StateFlow<ProtectedData> = _data.asStateFlow()

    private val _checks = MutableStateFlow<List<SecurityCheck>>(emptyList())
    val checks: StateFlow<List<SecurityCheck>> = _checks.asStateFlow()

    private var timerJob: Job? = null

    init {
        viewModelScope.launch {
            repository.restore()
                .onSuccess { session -> if (session != null) onLoggedIn(session) else _state.value = AuthState.LoggedOut() }
                .onFailure { _state.value = AuthState.LoggedOut(it.message) }
        }
    }

    // ---------- Форма входу ----------

    fun onUsernameChange(v: String) = _form.update { it.copy(username = v, usernameError = null, error = null) }
    fun onPasswordChange(v: String) = _form.update { it.copy(password = v, passwordError = null, error = null) }
    fun onShortTokenChange(v: Boolean) = _form.update { it.copy(shortToken = v) }

    /** Швидке заповнення форми тестовим обліковим записом. */
    fun fillDemo(username: String, password: String) = _form.update { it.copy(username = username, password = password, usernameError = null, passwordError = null, error = null) }

    fun login() {
        val f = _form.value
        // Базова валідація на клієнті: порожні поля не надсилаються на сервер
        val userErr = if (f.username.isBlank()) "Введіть логін" else null
        val passErr = when {
            f.password.isEmpty() -> "Введіть пароль"
            f.password.length < 6 -> "Пароль — щонайменше 6 символів"
            else -> null
        }
        if (userErr != null || passErr != null) {
            _form.update { it.copy(usernameError = userErr, passwordError = passErr) }
            return
        }
        _form.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            repository.login(f.username, f.password, if (f.shortToken) SHORT_TTL else NORMAL_TTL)
                .onSuccess { session ->
                    _form.value = LoginForm()
                    onLoggedIn(session)
                }
                .onFailure { e -> _form.update { it.copy(submitting = false, error = e.message) } }
        }
    }

    fun logout(notice: String? = null) {
        timerJob?.cancel()
        viewModelScope.launch {
            repository.logout()
            _data.value = ProtectedData()
            _checks.value = emptyList()
            _state.value = AuthState.LoggedOut(notice)
        }
    }

    private fun onLoggedIn(session: Session) {
        _state.value = AuthState.LoggedIn(session, secondsLeft(session))
        startExpiryTimer(session)
        loadStatus()
    }

    /** Щосекунди оновлює залишок терміну дії; коли він вичерпано — автоматичний вихід. */
    private fun startExpiryTimer(session: Session) {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                val left = secondsLeft(session)
                if (left <= 0) {
                    logout("Термін дії токена минув — увійдіть повторно")
                    break
                }
                _state.update { if (it is AuthState.LoggedIn) it.copy(secondsLeft = left) else it }
                delay(1_000)
            }
        }
    }

    private fun secondsLeft(s: Session) = s.claims.expiresAt - nowMs() / 1000

    // ---------- Захищені ресурси ----------

    private val role: Role? get() = (state.value as? AuthState.LoggedIn)?.session?.claims?.roleOrNull

    val canRunAts get() = role == Role.OPERATOR || role == Role.ADMIN
    val canSeeAudit get() = role == Role.ADMIN

    fun loadStatus() = protectedCall { api ->
        val status = api.reserveStatus()
        val audit = if (canSeeAudit) api.audit() else emptyList()
        _data.update { it.copy(status = status, audit = audit) }
    }

    fun runAtsTest() = protectedCall { api ->
        val result = api.runAtsTest()
        _data.update { it.copy(atsResult = result) }
        if (canSeeAudit) _data.update { it.copy(audit = api.audit()) }
    }

    /**
     * Єдина обробка помилок захищених запитів: 401 — сесія недійсна, вихід з повідомленням;
     * 403 — лише повідомлення (сесія лишається); мережа — повідомлення з можливістю повторити.
     */
    private fun protectedCall(block: suspend (AuthApi) -> Unit) {
        viewModelScope.launch {
            _data.update { it.copy(loading = true, error = null) }
            repository.call(block)
                .onSuccess { _data.update { it.copy(loading = false) } }
                .onFailure { e ->
                    if (e is AuthError.Unauthorized) logout("${e.message}. Увійдіть повторно.")
                    else _data.update { it.copy(loading = false, error = e.message) }
                }
        }
    }

    // ---------- Демонстрація захисту (сервер має відхилити кожен запит) ----------

    fun checkWithoutToken() = securityCheck("Запит без токена") { it.reserveStatusAnonymous() }

    fun checkForgedToken() {
        val forged = repository.forgedToken() ?: return
        securityCheck("Підроблений токен (role = ADMIN, exp + 24 год)") { it.reserveStatusWithToken("Bearer $forged") }
    }

    /** Для адміністратора заборонених дій немає, тому перевіряється токен неправильного формату. */
    fun checkForbiddenAction() =
        if (canSeeAudit) securityCheck("Токен неправильного формату") { it.reserveStatusWithToken("Bearer invalid.token") }
        else securityCheck("Дія понад права ролі") { api -> if (canRunAts) api.audit() else api.runAtsTest() }

    private fun securityCheck(title: String, block: suspend (AuthApi) -> Any) {
        viewModelScope.launch {
            val result = repository.call(block)
            val check = result.fold(
                onSuccess = { SecurityCheck(title, "Сервер виконав запит — захист не спрацював", passed = false) },
                onFailure = { e ->
                    val code = when (e) {
                        is AuthError.Unauthorized -> "401"
                        is AuthError.Forbidden -> "403"
                        else -> "помилка"
                    }
                    SecurityCheck(title, "$code: ${e.message}", passed = e is AuthError.Unauthorized || e is AuthError.Forbidden)
                }
            )
            _checks.update { (listOf(check) + it).take(3) }
            if (canSeeAudit) repository.call { it.audit() }.onSuccess { a -> _data.update { it.copy(audit = a) } }
        }
    }

    companion object {
        const val NORMAL_TTL = 300
        const val SHORT_TTL = 20

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { AuthViewModel(AuthRepository.create(checkNotNull(this[APPLICATION_KEY]))) }
        }
    }
}
