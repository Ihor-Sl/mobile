package com.example.myapplication.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication.pw10.ui.OutageReadinessScreen
import com.example.myapplication.pw11.ui.AuthScreen
import com.example.myapplication.pw12.ui.AutonomyScreen
import com.example.myapplication.pw9.ui.FuelReserveScreen

/**
 * Кореневий екран: перемикач практичних занять зверху та вміст обраного заняття під ним.
 *
 * UI-стан: [selected] зберігається через rememberSaveable, тому обране заняття
 * не губиться при повороті екрана.
 */
@Composable
fun PracticeHost() {
    var selected by rememberSaveable { mutableStateOf(PracticeWork.PW9) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Відступ під статус-бар: у edge-to-edge режимі його не додає Scaffold
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    PracticeSelector(selected = selected, onSelectedChange = { selected = it })
                    Text(
                        text = selected.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                // Форми не мають ховатися під клавіатурою
                .imePadding()
                .fillMaxSize()
        ) {
            // Відображаємо екран, що відповідає обраному практичному заняттю
            when (selected) {
                PracticeWork.PW9 -> FuelReserveScreen()
                PracticeWork.PW10 -> OutageReadinessScreen()
                PracticeWork.PW11 -> AuthScreen()
                PracticeWork.PW12 -> AutonomyScreen()
            }
        }
    }
}

/** Сегментований перемикач ПЗ 9 … ПЗ 12. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeSelector(
    selected: PracticeWork,
    onSelectedChange: (PracticeWork) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = PracticeWork.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            SegmentedButton(
                selected = item == selected,
                onClick = { onSelectedChange(item) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = items.size),
                label = { Text(item.label) }
            )
        }
    }
}
