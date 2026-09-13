package com.colonelpanic.eva.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.conversation.ConversationEntry
import com.colonelpanic.eva.conversation.ConversationState
import com.colonelpanic.eva.conversation.EntryStatus
import com.colonelpanic.eva.conversation.ProviderStatus
import com.colonelpanic.eva.ui.theme.EvaTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvaApp(
    state: ConversationState,
    onSubmit: (String) -> Unit,
    onConnect: (String) -> Unit = {},
    onDisconnect: () -> Unit = {},
    onVoice: (String, Boolean) -> Unit = { _, _ -> },
    onToggleMicrophone: () -> Unit = {},
    onTogglePlayback: () -> Unit = {},
    denial: MicrophoneDenial? = null,
    onRetryMicrophone: () -> Unit = {},
    onListenOnlyInstead: () -> Unit = {},
    onDismissDenial: () -> Unit = {},
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val storageFailed = state.errorMessage != null
    val canSend =
        draft.isNotBlank() && !state.isLoading && !state.isSubmitting && !storageFailed &&
            state.providerStatus == ProviderStatus.CONNECTED &&
            !state.voiceMode
    val listState = rememberLazyListState()

    LaunchedEffect(state.entries.lastOrNull()?.id) {
        if (state.entries.isNotEmpty()) listState.scrollToItem(0)
    }

    fun send() {
        if (!canSend) return
        onSubmit(draft.trim())
        draft = ""
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("EVA", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = state.providerLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
                ProviderConnection(state, onConnect, onDisconnect, onVoice, denial, onRetryMicrophone, onListenOnlyInstead, onDismissDenial)
                if (state.voiceMode && state.providerStatus != ProviderStatus.DISCONNECTED) {
                    Text(
                        text = voiceSessionLabel(state.mediaControls.microphoneAvailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    VoiceControls(
                        state.mediaState,
                        state.mediaControls,
                        true,
                        {},
                        onDisconnect,
                        onToggleMicrophone,
                        onTogglePlayback,
                        Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        },
        bottomBar = {
            Composer(
                draft = draft,
                onDraftChange = { draft = it },
                enabled = !storageFailed,
                canSend = canSend,
                supportingText = composerHint(state),
                onSend = ::send,
            )
        },
    ) { innerPadding ->
        if (state.isLoading) {
            LoadingHistory(Modifier.padding(innerPadding))
        } else {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
            ) {
                state.errorMessage?.let { message ->
                    item(key = "storage-error") { StorageErrorBanner(message) }
                }
                if (state.entries.isEmpty()) {
                    item(key = "empty") {
                        EmptyConversation(onSampleSelected = { draft = it }, enabled = !storageFailed)
                    }
                }
                items(state.entries.asReversed(), key = { it.id }) { entry ->
                    ConversationEntryItem(entry)
                }
            }
        }
    }
}

internal fun voiceSessionLabel(microphoneAvailable: Boolean): String =
    if (microphoneAvailable) {
        "Voice session · phone actions unavailable"
    } else {
        "Listen-only session · your microphone is off · phone actions unavailable"
    }

internal fun composerHint(state: ConversationState): String {
    val voiceConnected = state.voiceMode && state.providerStatus == ProviderStatus.CONNECTED
    val listening = voiceConnected && !state.mediaControls.microphoneAvailable
    return when {
        state.errorMessage != null -> "Sending is paused until you restart EVA."
        state.isLoading -> "Loading your action history…"
        state.isSubmitting -> "Working on your last request…"
        listening -> "Listening only: EVA can speak, but nothing you say is captured. Disconnect to use text."
        voiceConnected -> "Speak to EVA, or disconnect to use text."
        state.voiceMode -> "Setting up voice…"
        state.providerStatus != ProviderStatus.CONNECTED -> "Connect to start a conversation."
        else -> "Ask naturally. EVA chooses from available actions."
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    enabled: Boolean,
    canSend: Boolean,
    supportingText: String,
    onSend: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier =
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)
                    .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                enabled = enabled,
                singleLine = true,
                placeholder = { Text("Ask EVA…") },
                supportingText = { Text(supportingText) },
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Send,
                    ),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier =
                    Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Request to EVA" },
            )
            Button(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier.padding(bottom = 20.dp),
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
private fun LoadingHistory(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp).semantics { contentDescription = "Loading" })
            Text(
                text = "Loading your action history…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(name = "Empty", showBackground = true)
@Preview(name = "Empty dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EmptyPreview() {
    EvaTheme(dynamicColor = false) {
        EvaApp(state = ConversationState(isLoading = false), onSubmit = {})
    }
}

@Preview(name = "History", showBackground = true)
@Preview(name = "History dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HistoryPreview() {
    EvaTheme(dynamicColor = false) {
        EvaApp(state = ConversationState(entries = previewEntries, isLoading = false, isSubmitting = true), onSubmit = {})
    }
}

@Preview(name = "Storage error", showBackground = true)
@Composable
private fun StorageErrorPreview() {
    EvaTheme(dynamicColor = false) {
        EvaApp(
            state =
                ConversationState(
                    entries = previewEntries.take(2),
                    isLoading = false,
                    errorMessage = "EVA could not safely read or save action history. Restart EVA before sending another request.",
                ),
            onSubmit = {},
        )
    }
}

private val previewEntries =
    listOf(
        ConversationEntry(
            id = "1",
            request = "map Golden Gate Park",
            response = "Map search opened.",
            status = EntryStatus.HANDED_OFF,
            destination = "Golden Gate Park",
        ),
        ConversationEntry(
            id = "2",
            request = "set a timer for ten minutes",
            response = "Try ‘map <place>’, ‘navigate to <place>’, or ‘text <number>: <message>’.",
            status = EntryStatus.NOT_EXECUTED,
        ),
        ConversationEntry(
            id = "3",
            request = "map Ocean Beach",
            response = "EVA could not confirm whether the map request was opened. Check your map app before trying again.",
            status = EntryStatus.UNKNOWN,
            destination = "Ocean Beach",
        ),
        ConversationEntry(
            id = "4",
            request = "map Twin Peaks",
            response = "No app is available to open this map search. Install a map app and try again.",
            status = EntryStatus.FAILED,
            destination = "Twin Peaks",
        ),
        ConversationEntry(
            id = "5",
            request = "open maps to 1 Ferry Building",
            response = "Opening a map app…",
            status = EntryStatus.DISPATCHING,
            destination = "1 Ferry Building",
        ),
    )
