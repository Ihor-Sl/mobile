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
import com.example.myapplication.pw1.composables.EmergencyMonitorApp
import com.example.myapplication.pw2.composables.EmergencyCalculatorApp
import com.example.myapplication.pw3.composables.PowerModesApp
import com.example.myapplication.pw4.composables.AuthApp

/**
 * Кореневий екран: перемикач практичних занять зверху та вміст обраного заняття під ним.
 *
 * UI-стан: [selected] зберігається через rememberSaveable, тому обране заняття
 * не губиться при повороті екрана.
 */
@Composable
fun PracticeHost() {
    var selected by rememberSaveable { mutableStateOf(PracticeWork.PW1) }

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
                // Екран авторизації не має ховатися під клавіатурою
                .imePadding()
                .fillMaxSize()
        ) {
            // Відображаємо екран, що відповідає обраному практичному заняттю
            when (selected) {
                PracticeWork.PW1 -> EmergencyMonitorApp()
                PracticeWork.PW2 -> EmergencyCalculatorApp()
                PracticeWork.PW3 -> PowerModesApp()
                PracticeWork.PW4 -> AuthApp()
            }
        }
    }
}

/** Сегментований перемикач ПЗ 1 … ПЗ 4. */
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
