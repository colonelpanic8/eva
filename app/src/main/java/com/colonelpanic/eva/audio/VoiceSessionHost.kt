package com.colonelpanic.eva.audio

import kotlinx.coroutines.flow.StateFlow

/** The part of a live voice session the ongoing notification renders. */
data class VoiceSessionStatus(
    val state: RealtimeMediaState = RealtimeMediaState.Idle,
    val controls: MediaControls = MediaControls(),
)

/**
 * Implemented by the application so [VoiceSessionService] can report and control a session
 * without an activity: the notification outlives whatever screen started the call.
 */
interface VoiceSessionHost {
    val voiceSession: StateFlow<VoiceSessionStatus>

    fun toggleVoiceMicrophone()

    fun voiceUnavailable(reason: String)

    fun endVoiceSession()
}

/** Notification text and the label its microphone action should carry. */
data class VoiceNotificationContent(
    val text: String,
    val microphoneAction: String,
)

internal fun voiceNotificationContent(status: VoiceSessionStatus): VoiceNotificationContent =
    VoiceNotificationContent(
        text = voiceStatusLabel(status.state, status.controls),
        microphoneAction = if (status.controls.microphoneMuted) "Unmute" else "Mute",
    )
