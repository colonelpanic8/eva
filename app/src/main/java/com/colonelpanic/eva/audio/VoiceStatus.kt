package com.colonelpanic.eva.audio

internal fun RealtimeMediaState.isActive(): Boolean =
    when (this) {
        RealtimeMediaState.Idle, RealtimeMediaState.Closed, is RealtimeMediaState.Failed -> false
        RealtimeMediaState.Preparing, is RealtimeMediaState.OfferReady -> true
        RealtimeMediaState.Connecting, is RealtimeMediaState.Connected -> true
    }

internal fun voiceStatusLabel(
    state: RealtimeMediaState,
    controls: MediaControls,
): String =
    when (state) {
        RealtimeMediaState.Idle -> "Voice off"
        RealtimeMediaState.Preparing -> "Preparing audio…"
        is RealtimeMediaState.OfferReady -> "Waiting for the provider…"
        RealtimeMediaState.Connecting -> "Connecting voice…"
        is RealtimeMediaState.Connected -> connectedLabel(state, controls)
        is RealtimeMediaState.Failed -> state.reason.message
        RealtimeMediaState.Closed -> "Voice ended"
    }

private fun connectedLabel(
    state: RealtimeMediaState.Connected,
    controls: MediaControls,
): String =
    when {
        controls.focus != AudioFocusState.HELD -> "Voice paused: another app has audio"
        !state.remoteAudio -> "Voice connected, no provider audio yet"
        controls.playbackMuted -> "Voice connected, speaker stopped"
        controls.microphoneMuted -> "Voice connected, mic muted"
        else -> "Voice connected"
    }
