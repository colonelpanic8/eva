package com.colonelpanic.eva.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.colonelpanic.eva.conversation.ConversationEntry
import com.colonelpanic.eva.conversation.EntryGroup
import com.colonelpanic.eva.conversation.EntryStatus
import com.colonelpanic.eva.conversation.ProviderStatus
import com.colonelpanic.eva.conversation.ThreadController
import com.colonelpanic.eva.conversation.groups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.nio.channels.FileLock

/**
 * EVA as a tray app: an icon in the panel, and a compact conversation window it toggles. The
 * window's close button hides it; Quit in the window ends the app. Returns the exit status.
 */
fun runTray(
    paths: DesktopPaths,
    lock: FileLock,
): Int {
    val host = DesktopHost(paths, Dispatchers.Main, lock)
    val shown = MutableStateFlow(true)
    val summons = SummonListener(paths.summonSocket) { shown.value = true }
    val (tray, problems) = PanelIcon.show { shown.value = !shown.value }
    if (tray == null) System.err.println("No panel tray is available (${problems.joinToString("; ")}); closing the window quits EVA.")
    application(exitProcessOnExit = false) {
        val visible by shown.collectAsState()
        Window(
            onCloseRequest = { if (tray != null) shown.value = false else exitApplication() },
            visible = visible,
            title = "EVA",
            icon = painterResource("eva-icon.png"),
            alwaysOnTop = true,
            state = rememberWindowState(width = 440.dp, height = 640.dp),
        ) {
            MaterialTheme(if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Conversation(host.controller, onQuit = ::exitApplication) }
            }
        }
    }
    tray?.close()
    summons.close()
    host.close()
    return 0
}

@Composable
private fun Conversation(
    controller: ThreadController,
    onQuit: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val shownGroups = groups(state.entries)

    LaunchedEffect(Unit) {
        controller.state.first { !it.isLoading }
        if (controller.state.value.threadId == null) startThread(controller, Dispatchers.Main)
        if (connect(controller, Dispatchers.Main) == null) problem = connectionProblem(controller.state.value)
    }
    LaunchedEffect(state.entries.size, state.entries.lastOrNull()?.response) {
        if (shownGroups.isNotEmpty()) list.animateScrollToItem(shownGroups.lastIndex)
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || !state.acceptsTextInput) return
        controller.submit(text)
        draft = ""
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                problem ?: status(state.providerStatus, state.providerLabel, state.working),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(
                onClick = {
                    scope.launch {
                        problem = null
                        if (reconnectToNewThread(controller, Dispatchers.Main) == null) problem = connectionProblem(controller.state.value)
                    }
                },
                enabled = !state.working,
            ) { Text("New") }
            TextButton(onClick = onQuit) { Text("Quit") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shownGroups, key = { it.entry.id }) { Turn(it) }
        }
        state.providerMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                draft,
                { draft = it },
                Modifier.weight(1f).onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                        send()
                        true
                    } else {
                        false
                    }
                },
                placeholder = { Text("Ask EVA") },
                maxLines = 4,
            )
            Button(::send, enabled = state.acceptsTextInput && draft.isNotBlank()) { Text("Send") }
        }
    }
}

@Composable
private fun Turn(group: EntryGroup) {
    val entry = group.entry
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (entry.request.isNotBlank()) Bubble(entry.request, mine = true)
        group.actions.forEach { Action(it) }
        when {
            entry.capabilityId != null -> Action(entry)
            entry.status == EntryStatus.SESSION -> Text(entry.response, style = MaterialTheme.typography.labelSmall)
            entry.response.isNotBlank() -> Bubble(entry.response, mine = false)
        }
    }
}

@Composable
private fun Action(entry: ConversationEntry) {
    val outcome =
        entry.status.name
            .lowercase()
            .replace('_', ' ')
    Text(
        "· ${entry.actionTitle ?: entry.capabilityId}: $outcome${entry.result?.let { " — $it" }.orEmpty()}",
        style = MaterialTheme.typography.bodySmall,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Bubble(
    text: String,
    mine: Boolean,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

private fun status(
    provider: ProviderStatus,
    label: String,
    working: Boolean,
): String =
    when {
        working -> "Working…"
        provider == ProviderStatus.CONNECTED -> label
        provider == ProviderStatus.CONNECTING -> "Connecting…"
        else -> "Not connected"
    }
