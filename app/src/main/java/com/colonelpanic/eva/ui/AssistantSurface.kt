package com.colonelpanic.eva.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.R
import com.colonelpanic.eva.audio.AudioFocusState
import com.colonelpanic.eva.audio.MediaControls
import com.colonelpanic.eva.audio.RealtimeMediaState
import com.colonelpanic.eva.audio.isActive
import com.colonelpanic.eva.audio.voiceStatusLabel
import com.colonelpanic.eva.conversation.ConversationEntry
import com.colonelpanic.eva.conversation.ConversationState
import com.colonelpanic.eva.conversation.EntryGroup
import com.colonelpanic.eva.conversation.EntryStatus
import com.colonelpanic.eva.conversation.groups
import com.colonelpanic.eva.ui.theme.EvaTheme

/**
 * What the assist gesture puts on screen: a small card floating over the app the user was
 * already in, not a replacement for it. The app stays fully visible, undimmed, around the
 * card, and tapping it dismisses the card rather than returning the user to EVA.
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
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Dismiss EVA",
                        onClick = onDismiss,
                    ),
        )
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
            modifier =
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    // The card is not itself a button; this only stops its taps reaching the dismiss layer.
                    .pointerInput(Unit) { detectTapGestures {} },
        ) {
            Column(
                modifier = Modifier.animateContentSize().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (needsMicrophone) {
                    // A session has no activity to ask from, so the grant has to be collected in the app.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "EVA needs the microphone before it can listen.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        FilledTonalButton(onClick = onOpenApp) { Text("Open EVA") }
                    }
                    return@Column
                }
                if (locked) {
                    Text(
                        text = "Unlock to see the conversation.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                } else {
                    LatestTurn(state.entries)
                }
                AssistantControls(
                    state = state.mediaState,
                    controls = state.mediaControls,
                    onStart = onStart,
                    onStop = onStop,
                    onToggleMicrophone = onToggleMicrophone,
                    onTogglePlayback = onTogglePlayback,
                    onOpenApp = onOpenApp,
                )
            }
        }
    }
}

/** Only the turn in progress: the full history is one tap away in the app. */
@Composable
private fun LatestTurn(entries: List<ConversationEntry>) {
    val group = groups(entries).lastOrNull { it.entry.status != EntryStatus.SESSION } ?: return
    val entry = group.entry
    val children = if (entry.capabilityId != null && entry.request.isBlank()) listOf(EntryGroup(entry)) else group.children
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Column(
        modifier =
            Modifier
                .heightIn(max = 200.dp)
                .verticalScroll(scroll)
                .padding(end = 8.dp)
                .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (entry.request.isNotBlank()) {
            Text(
                text = entry.request,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        children.forEach { child ->
            val leg = child.entry.textLeg
            if (leg == null) {
                CompactAction(child.entry)
            } else {
                CompactRow("Text agent", leg.presentation())
                child.actions.forEach { action -> Box(Modifier.padding(start = 16.dp)) { CompactAction(action) } }
            }
        }
        if (entry.response.isNotBlank() && children.none { it.entry == entry }) {
            if (entry.status != EntryStatus.ANSWER) StatusLine(entry.status.presentation())
            Text(text = entry.response, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun CompactAction(action: ConversationEntry) =
    CompactRow(action.actionTitle ?: action.capabilityId ?: "Action", action.status.presentation())

@Composable
private fun CompactRow(
    title: String,
    status: StatusPresentation,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusIndicator(status)
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(text = status.label, style = MaterialTheme.typography.labelMedium, color = status.color())
    }
}

@Composable
private fun AssistantControls(
    state: RealtimeMediaState,
    controls: MediaControls,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleMicrophone: () -> Unit,
    onTogglePlayback: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val active = state.isActive()
    Row(verticalAlignment = Alignment.CenterVertically) {
        VoiceOrb(state, controls)
        Text(
            text = voiceStatusLabel(state, controls),
            style = MaterialTheme.typography.bodyMedium,
            color = if (state is RealtimeMediaState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        if (active) {
            IconButton(onClick = onToggleMicrophone) {
                Icon(
                    painter = painterResource(if (controls.microphoneMuted) R.drawable.ic_mic_off else R.drawable.ic_mic),
                    contentDescription = if (controls.microphoneMuted) "Unmute microphone" else "Mute microphone",
                )
            }
            IconButton(onClick = onTogglePlayback) {
                Icon(
                    painter = painterResource(if (controls.playbackMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
                    contentDescription = if (controls.playbackMuted) "Resume speaker" else "Stop speaker",
                )
            }
        }
        IconButton(onClick = onOpenApp) {
            Icon(painter = painterResource(R.drawable.ic_open_in_full), contentDescription = "Open EVA")
        }
        if (active) {
            FilledIconButton(
                onClick = onStop,
                colors =
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
            ) { Icon(painter = painterResource(R.drawable.ic_call_end), contentDescription = "Stop voice") }
        } else {
            FilledIconButton(onClick = onStart) {
                Icon(painter = painterResource(R.drawable.ic_mic), contentDescription = "Start voice")
            }
        }
    }
}

/** Pulses while EVA can hear the user, spins while the call is still being set up. */
@Composable
private fun VoiceOrb(
    state: RealtimeMediaState,
    controls: MediaControls,
) {
    val colors = MaterialTheme.colorScheme
    if (state.isActive() && state !is RealtimeMediaState.Connected) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
        return
    }
    val listening = state is RealtimeMediaState.Connected && !controls.microphoneMuted && controls.focus == AudioFocusState.HELD
    val brush =
        when {
            state is RealtimeMediaState.Failed -> Brush.linearGradient(listOf(colors.error, colors.error))
            state is RealtimeMediaState.Connected -> Brush.linearGradient(listOf(colors.primary, colors.tertiary))
            else -> Brush.linearGradient(listOf(colors.outlineVariant, colors.outline))
        }
    val pulse =
        if (listening) {
            rememberInfiniteTransition(label = "orb")
                .animateFloat(
                    initialValue = 0.8f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
                    label = "pulse",
                )
        } else {
            null
        }
    Box(
        modifier =
            Modifier
                .size(24.dp)
                .graphicsLayer {
                    val scale = pulse?.value ?: 1f
                    scaleX = scale
                    scaleY = scale
                }.background(brush, CircleShape),
    )
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
