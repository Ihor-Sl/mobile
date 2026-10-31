package com.example.myapplication.pw11.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.pw11.auth.Role
import com.example.myapplication.pw11.ui.components.AtsTestCard
import com.example.myapplication.pw11.ui.components.AuditCard
import com.example.myapplication.pw11.ui.components.DemoAccountsCard
import com.example.myapplication.pw11.ui.components.LoginCard
import com.example.myapplication.pw11.ui.components.ReserveStatusCard
import com.example.myapplication.pw11.ui.components.SecurityChecksCard
import com.example.myapplication.pw11.ui.components.SessionCard
import com.example.myapplication.pw11.viewmodel.AuthState
import com.example.myapplication.pw11.viewmodel.AuthViewModel
import com.example.myapplication.ui.common.MessageBanner
import com.example.myapplication.ui.common.ScreenTitle
import com.example.myapplication.ui.common.Tone

private val WIDE_SCREEN = 720.dp

/**
 * ПЗ 11, просунутий рівень: вхід до пульта аварійного живлення за JWT.
 *
 * Екран обирає вміст за станом автентифікації: відновлення сесії (індикатор), форма входу
 * або пульт. Склад пульта залежить від ролі з токена: інспектор лише переглядає стан,
 * диспетчер також запускає тест АВР, головний енергетик бачить журнал входів.
 */
@Composable
fun AuthScreen(viewModel: AuthViewModel = viewModel(factory = AuthViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val data by viewModel.data.collectAsStateWithLifecycle()
    val checks by viewModel.checks.collectAsStateWithLifecycle()

    when (val s = state) {
        AuthState.Restoring -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("Перевірка збереженого токена…", modifier = Modifier.padding(top = 12.dp))
            }
        }

        is AuthState.LoggedOut -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ScreenTitle("Пульт аварійного живлення", "Вхід за JWT-токеном (імітаційний сервер)")
                s.notice?.let { MessageBanner(it, Tone.WARN) }
                LoginCard(
                    form,
                    onUsername = viewModel::onUsernameChange,
                    onPassword = viewModel::onPasswordChange,
                    onShortToken = viewModel::onShortTokenChange,
                    onSubmit = viewModel::login,
                )
                DemoAccountsCard(onPick = viewModel::fillDemo)
            }
        }

        is AuthState.LoggedIn -> {
            val role = s.session.claims.roleOrNull
            val session: @Composable () -> Unit = { SessionCard(s.session, s.secondsLeft, onLogout = { viewModel.logout() }) }
            val status: @Composable () -> Unit = { ReserveStatusCard(data, onRefresh = viewModel::loadStatus) }
            // Секції, видимість яких залежить від ролі
            val ats: @Composable () -> Unit = {
                if (role == Role.OPERATOR || role == Role.ADMIN) AtsTestCard(data.atsResult, data.loading, viewModel::runAtsTest)
            }
            val audit: @Composable () -> Unit = { if (role == Role.ADMIN) AuditCard(data.audit) }
            val security: @Composable () -> Unit = {
                SecurityChecksCard(
                    checks,
                    thirdLabel = if (role == Role.ADMIN) "Неправильний формат" else "Дія понад права",
                    onWithoutToken = viewModel::checkWithoutToken,
                    onForged = viewModel::checkForgedToken,
                    onForbidden = viewModel::checkForbiddenAction,
                )
            }

            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= WIDE_SCREEN
                if (wide) {
                    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            session(); status(); ats()
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            security(); audit()
                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        session(); status(); ats(); audit(); security()
                        if (role == Role.VIEWER) {
                            Text(
                                "Тест АВР і журнал входів приховано: роль «${Role.VIEWER.label}» має лише право перегляду.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
