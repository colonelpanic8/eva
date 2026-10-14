package com.colonelpanic.eva.wear

import com.colonelpanic.eva.diagnostics.EvaTrace
import com.colonelpanic.eva.providers.ProviderEvent

/** Per attachment attribution; never stores speech content or borrows another call's evidence. */
class WatchSessionTrace(
    private val id: String,
    private val startedAt: Long,
) {
    private var connected = false
    private var connectedAt: Long? = null
    private var speech = false
    private var transcript = false

    fun event(
        event: ProviderEvent,
        atMillis: Long,
    ) {
        when (event) {
            is ProviderEvent.Connected -> {
                connected = true
                connectedAt = atMillis
                EvaTrace.info("wear.session.connected", "request" to id)
            }

            ProviderEvent.UserSpeaking, is ProviderEvent.SpeechInputStarted -> {
                speech = true
            }

            is ProviderEvent.Transcript -> {
                if (event.role == "user" && event.text.isNotBlank()) {
                    speech = true
                    transcript = true
                }
            }

            else -> {
                Unit
            }
        }
    }

    fun closed(
        atMillis: Long,
        threadId: String?,
        reason: String,
    ) {
        val duration = (atMillis - (connectedAt ?: startedAt)).coerceAtLeast(0)
        EvaTrace.info(
            "wear.session.closed",
            "request" to id,
            "thread" to threadId,
            "durationMillis" to duration,
            "startupMillis" to connectedAt?.let { it - startedAt },
            "connected" to connected,
            "userSpeech" to speech,
            "userTranscript" to transcript,
            "endedImmediately" to (connected && duration < IMMEDIATE_END_MILLIS),
            "likelyFalsePositive" to (connected && (duration < IMMEDIATE_END_MILLIS || !speech)),
            "reason" to reason,
        )
    }

    companion object {
        const val IMMEDIATE_END_MILLIS = 5000L
    }
}

fun watchStartFailure(
    enabled: Boolean,
    assistant: Boolean,
    microphone: Boolean,
    busy: Boolean,
): String? =
    when {
        !enabled -> "Watch gestures are disabled. Enable Wrist gesture in EVA settings on the phone."
        !assistant -> "EVA is not the phone's default assistant. Select EVA in Android Settings → Default apps → Digital assistant app."
        !microphone -> "EVA has no microphone permission. Open EVA on the phone and grant microphone access."
        busy -> "An EVA voice session is already running. Finish that session before starting another from the watch."
        else -> null
    }
