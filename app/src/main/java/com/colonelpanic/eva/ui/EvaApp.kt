package com.colonelpanic.eva.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.colonelpanic.eva.audio.AudioFocusState
import com.colonelpanic.eva.audio.MediaControls
import com.colonelpanic.eva.audio.RealtimeMediaState
import com.colonelpanic.eva.conversation.ConversationEntry
import com.colonelpanic.eva.conversation.ConversationState
import com.colonelpanic.eva.conversation.EntryStatus
import com.colonelpanic.eva.conversation.ProviderStatus
import com.colonelpanic.eva.providers.openai.OpenAiModels
import com.colonelpanic.eva.ui.settings.SettingsActions
import com.colonelpanic.eva.ui.settings.SettingsScreen
import com.colonelpanic.eva.ui.settings.SettingsUiState
import com.colonelpanic.eva.ui.theme.EvaTheme
import kotlinx.coroutines.launch

internal enum class EvaDestination { CONVERSATION, SETTINGS }

/**
 * Two top-level destinations behind a navigation drawer. A navigation library would only
 * add a dependency to express what one saved enum and a back press already do.
 */
@Composable
fun EvaApp(
    state: ConversationState,
    settings: SettingsUiState,
    settingsActions: SettingsActions,
    onSubmit: (String) -> Unit,
    onConnect: () -> Unit = {},
    onVoice: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onToggleMicrophone: () -> Unit = {},
    onTogglePlayback: () -> Unit = {},
    denial: MicrophoneDenial? = null,
    onRetryMicrophone: () -> Unit = {},
    onDismissDenial: () -> Unit = {},
) {
    var destination by rememberSaveable { mutableStateOf(EvaDestination.CONVERSATION) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openDrawer = { scope.launch { drawer.open() } }

    // The drawer registers its own back handler while open, so this one only sees a back
    // press on settings with the drawer already closed.
    BackHandler(enabled = destination == EvaDestination.SETTINGS) {
        destination = EvaDestination.CONVERSATION
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            EvaDrawerSheet(
                providerLabel = state.providerLabel,
                current = destination,
                onSelect = { selected ->
                    destination = selected
                    scope.launch { drawer.close() }
                },
            )
        },
    ) {
        when (destination) {
            EvaDestination.CONVERSATION -> {
                ConversationScreen(
                    state = state,
                    hasCredential = settings.hasCredential,
                    onSubmit = onSubmit,
                    onConnect = onConnect,
                    onVoice = onVoice,
                    onDisconnect = onDisconnect,
                    onOpenDrawer = { openDrawer() },
                    onToggleMicrophone = onToggleMicrophone,
                    onTogglePlayback = onTogglePlayback,
                    denial = denial,
                    onRetryMicrophone = onRetryMicrophone,
                    onDismissDenial = onDismissDenial,
                )
            }

            EvaDestination.SETTINGS -> {
                SettingsScreen(
                    state = settings,
                    actions = settingsActions,
                    onOpenDrawer = { openDrawer() },
                )
            }
        }
    }
}

internal const val VOICE_SESSION_LABEL = "Voice session · ask for a phone action or just talk"

internal fun composerHint(state: ConversationState): String {
    val voiceConnected = state.voiceMode && state.providerStatus == ProviderStatus.CONNECTED
    return when {
        state.errorMessage != null -> "Sending is paused until you restart EVA."
        state.isLoading -> "Loading your action history…"
        state.isSubmitting -> "Working on your last request…"
        voiceConnected -> "Speak to EVA, or disconnect to use text."
        state.voiceMode -> "Setting up voice…"
        state.providerStatus != ProviderStatus.CONNECTED -> "Connect to start a conversation."
        else -> "Ask naturally. EVA chooses from available actions."
    }
}

@Preview(name = "Empty", showBackground = true)
@Preview(name = "Empty dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EmptyPreview() {
    EvaTheme(dynamicColor = false) {
        EvaApp(
            state = ConversationState(isLoading = false),
            settings = previewSettings,
            settingsActions = SettingsActions(),
            onSubmit = {},
        )
    }
}

@Preview(name = "History", showBackground = true)
@Preview(name = "History dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HistoryPreview() {
    EvaTheme(dynamicColor = false) {
        EvaApp(
            state = ConversationState(entries = previewEntries, isLoading = false, isSubmitting = true),
            settings = previewSettings,
            settingsActions = SettingsActions(),
            onSubmit = {},
        )
    }
}

@Preview(name = "Voice session", showBackground = true)
@Preview(name = "Voice session dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun VoiceSessionPreview() {
    EvaTheme(dynamicColor = false) {
        EvaApp(
            state =
                ConversationState(
                    entries = previewEntries.take(2),
                    isLoading = false,
                    providerStatus = ProviderStatus.CONNECTED,
                    providerLabel = "OpenAI · ${OpenAiModels.REALTIME}",
                    voiceMode = true,
                    mediaState = RealtimeMediaState.Connected(remoteAudio = true),
                    mediaControls = MediaControls(focus = AudioFocusState.HELD),
                ),
            settings = previewSettings,
            settingsActions = SettingsActions(),
            onSubmit = {},
        )
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
            settings = previewSettings,
            settingsActions = SettingsActions(),
            onSubmit = {},
        )
    }
}

private val previewSettings = SettingsUiState(account = "ivan@example.com", version = "0.10.0")

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
