package com.colonelpanic.eva.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.audio.AudioFocusState
import com.colonelpanic.eva.audio.MediaControls
import com.colonelpanic.eva.audio.RealtimeMediaState
import com.colonelpanic.eva.conversation.ConversationEntry
import com.colonelpanic.eva.conversation.ConversationState
import com.colonelpanic.eva.conversation.EntryStatus
import com.colonelpanic.eva.ui.theme.EvaTheme

private const val VISIBLE_ENTRIES = 3

/**
 * What the assist gesture puts on screen. A panel over the app the user was already in,
 * not a replacement for it: whatever prompted the request stays readable behind it, and
 * dismissing the panel returns the user to it rather than to EVA.
 */
@Composable
fun AssistantSurface(
    state: ConversationState,
    locked: Boolean,
    needsMicrophone: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleMicrophone: () -> Unit,
    onTogglePlayback: () -> Unit,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Dismiss EVA",
                        onClick = onDismiss,
                    ),
        )
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            // The panel is not itself a button; this only stops its taps reaching the scrim.
            modifier = Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures {} },
        ) {
            Column(
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "EVA", style = MaterialTheme.typography.titleLarge)
                if (needsMicrophone) {
                    // A session has no activity to ask from, so the grant has to be collected in the app.
                    Text(
                        text = "EVA needs the microphone before it can listen. Open EVA to allow it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onOpenApp) { Text("Open EVA") }
                    return@Column
                }
                if (locked) {
                    Text(
                        text = "Locked, so the conversation stays hidden. Unlock to read it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    AssistantTranscript(state.entries)
                }
                VoiceControls(
                    state = state.mediaState,
                    controls = state.mediaControls,
                    enabled = true,
                    onStart = onStart,
                    onStop = onStop,
                    onToggleMicrophone = onToggleMicrophone,
                    onTogglePlayback = onTogglePlayback,
                )
                TextButton(onClick = onOpenApp) { Text("Open EVA") }
            }
        }
    }
}

/** The last few turns only: the panel is a reminder of what was just said, not the history. */
@Composable
private fun AssistantTranscript(entries: List<ConversationEntry>) {
    if (entries.isEmpty()) {
        Text(
            text = "Ask a question, or request a phone action.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(
        modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        entries.takeLast(VISIBLE_ENTRIES).forEach { entry -> ConversationEntryItem(entry) }
    }
}

@Preview(name = "Assistant", showBackground = true)
@Preview(
    name = "Assistant dark",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun AssistantSurfacePreview() {
    EvaTheme {
        AssistantSurface(
            state =
                ConversationState(
                    entries =
                        listOf(
                            ConversationEntry(
                                id = "1",
                                request = "set a timer for three minutes",
                                response = "Timer started.",
                                status = EntryStatus.HANDED_OFF,
                            ),
                        ),
                    isLoading = false,
                    mediaState = RealtimeMediaState.Connected(remoteAudio = true),
                    mediaControls = MediaControls(focus = AudioFocusState.HELD),
                ),
            locked = false,
            needsMicrophone = false,
            onStart = {},
            onStop = {},
            onToggleMicrophone = {},
            onTogglePlayback = {},
            onOpenApp = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Assistant locked", showBackground = true)
@Composable
private fun AssistantSurfaceLockedPreview() {
    EvaTheme {
        AssistantSurface(
            state = ConversationState(isLoading = false),
            locked = true,
            needsMicrophone = false,
            onStart = {},
            onStop = {},
            onToggleMicrophone = {},
            onTogglePlayback = {},
            onOpenApp = {},
            onDismiss = {},
        )
    }
}
