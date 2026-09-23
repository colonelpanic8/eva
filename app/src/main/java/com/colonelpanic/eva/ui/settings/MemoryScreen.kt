package com.colonelpanic.eva.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.data.Memories
import com.colonelpanic.eva.data.MemoryNote
import com.colonelpanic.eva.ui.MenuButton
import com.colonelpanic.eva.ui.evaTopAppBarColors
import com.colonelpanic.eva.ui.theme.EvaTheme

/**
 * What EVA remembers across conversations. Notes it learned on its own wait here until the user
 * keeps or dismisses them; EVA can already search them in the meantime.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onOpenDrawer: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Memory") },
                navigationIcon = { MenuButton(onOpenDrawer) },
                colors = evaTopAppBarColors(),
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection("Learned, waiting for review") {
                if (state.memories.inbox.isEmpty()) {
                    SettingsRow(
                        title = "Nothing to review",
                        supporting = "Things EVA picks up in conversation appear here. It can use them before you review them.",
                    )
                }
                state.memories.inbox.forEach { note ->
                    NoteRow(note, "Learned") {
                        Row {
                            TextButton(onClick = { actions.onKeepMemory(note.name) }) { Text("Keep") }
                            TextButton(onClick = { actions.onForgetMemory(note.name) }) { Text("Dismiss") }
                        }
                    }
                }
            }
            SettingsDivider()
            SettingsSection("Kept") {
                if (state.memories.kept.isEmpty()) {
                    SettingsRow(title = "No notes kept", supporting = "Ask EVA to remember something, or keep a learned note.")
                }
                state.memories.kept.forEach { note ->
                    NoteRow(note, "Updated") {
                        TextButton(onClick = { actions.onForgetMemory(note.name) }) { Text("Forget") }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NoteRow(
    note: MemoryNote,
    verb: String,
    trailing: @Composable () -> Unit,
) {
    SettingsRow(
        title = note.name,
        supporting = "${note.text}\n$verb ${DateUtils.getRelativeTimeSpanString(note.updatedAtMillis)}",
        trailing = trailing,
    )
}

@Preview(name = "Memory", showBackground = true)
@Preview(name = "Memory dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MemoryPreview() {
    val now = System.currentTimeMillis()
    EvaTheme(dynamicColor = false) {
        MemoryScreen(
            state =
                SettingsUiState(
                    memories =
                        Memories(
                            kept = listOf(MemoryNote("Kyoto hotel", "Hotel Granvia, September 23–27", now - 86_400_000)),
                            inbox = listOf(MemoryNote("Coffee order", "Oat flat white, no sugar", now - 3_600_000, "thread")),
                        ),
                ),
            actions = SettingsActions(),
            onOpenDrawer = {},
        )
    }
}
