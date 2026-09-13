package com.colonelpanic.eva.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.audio.AudioFocusState
import com.colonelpanic.eva.audio.MediaControls
import com.colonelpanic.eva.audio.RealtimeMediaState

/** Stateless voice transport controls; the caller owns the session and signaling. */
@Composable
fun VoiceControls(
    state: RealtimeMediaState,
    controls: MediaControls,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleMicrophone: () -> Unit,
    onTogglePlayback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = state.isActive()
    Column(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = voiceStatusLabel(state, controls),
            style = MaterialTheme.typography.labelLarge,
            color = if (state is RealtimeMediaState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (active) {
                Button(onClick = onStop, enabled = enabled) { Text("Stop voice") }
            } else {
                Button(onClick = onStart, enabled = enabled) { Text("Start voice") }
            }
            if (controls.microphoneAvailable) {
                val microphoneAction = if (controls.microphoneMuted) "Unmute microphone" else "Mute microphone"
                OutlinedButton(
                    onClick = onToggleMicrophone,
                    enabled = enabled && active,
                    modifier = Modifier.semantics { contentDescription = microphoneAction },
                ) { Text(if (controls.microphoneMuted) "Unmute mic" else "Mute mic") }
            }
            OutlinedButton(
                onClick = onTogglePlayback,
                enabled = enabled && active,
                modifier = Modifier.semantics { contentDescription = if (controls.playbackMuted) "Resume speaker" else "Stop speaker" },
            ) { Text(if (controls.playbackMuted) "Resume speaker" else "Stop speaker") }
        }
    }
}

internal fun RealtimeMediaState.isActive(): Boolean =
    when (this) {
        RealtimeMediaState.Idle, RealtimeMediaState.Closed, is RealtimeMediaState.Failed -> false
        RealtimeMediaState.Preparing, is RealtimeMediaState.OfferReady -> true
        RealtimeMediaState.Connecting, is RealtimeMediaState.Connected -> true
    }

internal fun voiceStatusLabel(
    state: RealtimeMediaState,
    controls: MediaControls,
): String {
    val leg = if (controls.microphoneAvailable) "Voice" else "Listen-only"
    return when (state) {
        RealtimeMediaState.Idle -> "$leg off"
        RealtimeMediaState.Preparing -> "Preparing audio…"
        is RealtimeMediaState.OfferReady -> "Waiting for the provider…"
        RealtimeMediaState.Connecting -> "Connecting ${leg.lowercase()}…"
        is RealtimeMediaState.Connected -> connectedLabel(state, controls, leg)
        is RealtimeMediaState.Failed -> state.reason.message
        RealtimeMediaState.Closed -> "$leg ended"
    }
}

private fun connectedLabel(
    state: RealtimeMediaState.Connected,
    controls: MediaControls,
    leg: String,
): String =
    when {
        controls.focus != AudioFocusState.HELD -> "$leg paused: another app has audio"
        !state.remoteAudio -> "$leg connected, no provider audio yet"
        controls.playbackMuted -> "$leg connected, speaker stopped"
        !controls.microphoneAvailable -> "Listen-only connected, your mic is off"
        controls.microphoneMuted -> "Voice connected, mic muted"
        else -> "Voice connected"
    }
