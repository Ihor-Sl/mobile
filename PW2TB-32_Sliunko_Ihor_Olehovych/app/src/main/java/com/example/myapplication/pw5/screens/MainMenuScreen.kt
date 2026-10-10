package com.example.myapplication.pw5.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.pw5.components.MenuCard
import com.example.myapplication.pw5.components.ScreenHeader
import com.example.myapplication.pw5.data.FacilityCatalog
import com.example.myapplication.pw5.data.OperatorSession
import com.example.myapplication.pw5.data.ReserveAssessor

/** Пункт меню: назва, пояснення та дія при натисканні. */
private data class MenuItem(val title: String, val description: String, val onClick: () -> Unit)

/**
 * Головне меню застосунку.
 *
 * Екран не знає про NavController: переходи передаються як лямбди, а навігацію виконує
 * EmergencyNavApp. Завдяки цьому екран легко перевикористати та переглянути в Preview.
 *
 * Адаптивність: на широкому екрані (≥ 600 dp) пункти меню розташовуються в рядок, на вузькому — в колонку.
 */
@Composable
fun MainMenuScreen(
    session: OperatorSession,
    onOpenFacilities: () -> Unit,
    onOpenSummary: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val items = listOf(
        MenuItem("Об'єкти та резерв", "Перелік об'єктів, автономність і рекомендації", onOpenFacilities),
        MenuItem("Зведення готовності", "Стан резерву всіх об'єктів на одному екрані", onOpenSummary),
        MenuItem("Профіль оператора", "Ім'я, зміна та режим розрахунку", onOpenProfile),
    )

    AdaptiveScreen {
        ScreenHeader(
            title = "Головне меню",
            subtitle = "Диспетчерська системи аварійного електропостачання"
        )
        SessionCard(session, modifier = Modifier.padding(vertical = 16.dp))

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= WIDE_SCREEN) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items.forEach { MenuCard(it.title, it.description, it.onClick, Modifier.weight(1f)) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items.forEach { MenuCard(it.title, it.description, it.onClick) }
                }
            }
        }
    }
}

/**
 * Картка стану користувача: хто на зміні та який режим розрахунку збережено.
 * Значення приходять з екрана профілю — при поверненні в меню картка оновлюється автоматично.
 */
@Composable
private fun SessionCard(session: OperatorSession, modifier: Modifier = Modifier) {
    // Зведення перераховується лише при зміні режиму розрахунку
    val summary = remember(session.criticalOnly) {
        ReserveAssessor.summarize(FacilityCatalog.facilities, session.criticalOnly)
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (session.isSignedIn) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (session.isSignedIn) "Оператор: ${session.name}" else "Оператора не вказано",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (session.isSignedIn) "Зміна: ${session.shift.title}"
                else "Заповніть профіль оператора, щоб розпочати зміну",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Режим розрахунку: " + if (session.criticalOnly) "лише критичні споживачі" else "усе навантаження",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Готові до аварії: ${summary.ready} з ${summary.total} об'єктів",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
