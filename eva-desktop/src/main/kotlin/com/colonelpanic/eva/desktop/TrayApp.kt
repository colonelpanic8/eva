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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.channels.FileLock

/** What the panel icon and `summon` ask of the window. */
private enum class WindowRequest { TOGGLE, SHOW }

/**
 * EVA as a tray app: an icon in the panel, and a compact conversation window it toggles. The
 * window's close button hides it while a panel shows the icon; Quit in the window ends the app.
 * Returns the exit status.
 */
fun runTray(
    paths: DesktopPaths,
    lock: FileLock,
): Int {
    val host = DesktopHost(paths, Dispatchers.Main, lock)
    val requests = Channel<WindowRequest>(Channel.UNLIMITED)
    val summons = SummonListener(paths.summonSocket) { requests.trySend(WindowRequest.SHOW) }
    val (tray, problems) = PanelIcon.show { requests.trySend(WindowRequest.TOGGLE) }
    if (tray == null) System.err.println("No panel tray is available (${problems.joinToString("; ")}); closing the window quits EVA.")
    application(exitProcessOnExit = false) {
        var shown by remember { mutableStateOf(true) }
        var raises by remember { mutableStateOf(0) }
        val windowState = rememberWindowState(width = 440.dp, height = 640.dp)
        LaunchedEffect(Unit) {
            for (request in requests) {
                if (request == WindowRequest.TOGGLE && shown && !windowState.isMinimized) {
                    shown = false
                } else {
                    shown = true
                    windowState.isMinimized = false
                    raises++
                }
            }
        }
        Window(
            onCloseRequest = { if (tray?.visible() == true) shown = false else exitApplication() },
            visible = shown,
            title = "EVA",
            icon = painterResource("eva-icon.png"),
            alwaysOnTop = true,
            state = windowState,
        ) {
            LaunchedEffect(raises) {
                window.toFront()
                window.requestFocus()
            }
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
    // Starting, connecting, and switching threads run one at a time, never beside a request.
    val sessions = remember { Mutex() }
    var changing by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val shownGroups = groups(state.entries)

    suspend fun changeSession(change: suspend () -> String?) =
        sessions.withLock {
            changing = true
            problem = null
            try {
                if (change() == null) problem = connectionProblem(controller.state.value)
            } finally {
                changing = false
            }
        }

    LaunchedEffect(Unit) {
        changeSession {
            controller.state.first { !it.isLoading }
            if (controller.state.value.threadId == null) startThread(controller, Dispatchers.Main)
            connect(controller, Dispatchers.Main)
        }
    }
    LaunchedEffect(state.entries.size, state.entries.lastOrNull()?.response) {
        if (shownGroups.isNotEmpty()) list.animateScrollToItem(shownGroups.lastIndex)
    }

    val canSend = !changing && state.acceptsTextInput
    val idle = !changing && !state.isLoading && !state.isSubmitting && !state.working && state.providerStatus != ProviderStatus.CONNECTING

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || !canSend) return
        if (text.length > ThreadController.MAX_REQUEST_CHARS) {
            problem = ThreadController.requestTooLong(text.length, ThreadController.MAX_REQUEST_CHARS)
            return
        }
        controller.submit(text)
        // The controller marks an accepted request at once; a refused one keeps its draft.
        if (controller.state.value.isSubmitting) {
            draft = ""
            problem = null
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                problem ?: status(state.providerStatus, state.providerLabel, state.working || changing),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(
                onClick = { scope.launch { changeSession { reconnectToNewThread(controller, Dispatchers.Main) } } },
                enabled = idle,
            ) { Text("New") }
            TextButton(onClick = onQuit) { Text("Quit") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shownGroups, key = { it.entry.id }) { Group(it) }
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
            Button(::send, enabled = canSend && draft.isNotBlank()) { Text("Send") }
        }
    }
}

/** A turn with what ran inside it, nested as the controller groups it, including text legs' own actions. */
@Composable
private fun Group(group: EntryGroup) {
    val entry = group.entry
    val leg = entry.textLeg
    when {
        leg != null -> {
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    leg.task?.let { "Continued in text: $it" } ?: "Continued after the connection ended",
                    style = MaterialTheme.typography.labelSmall,
                )
                group.children.forEach { Group(it) }
            }
        }

        entry.capabilityId != null -> {
            Action(entry)
            group.children.forEach { Group(it) }
        }

        else -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (entry.request.isNotBlank()) Bubble(entry.request, mine = true)
                group.children.forEach { Group(it) }
                when {
                    entry.status == EntryStatus.SESSION -> Text(entry.response, style = MaterialTheme.typography.labelSmall)
                    entry.response.isNotBlank() -> Bubble(entry.response, mine = false)
                }
            }
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
