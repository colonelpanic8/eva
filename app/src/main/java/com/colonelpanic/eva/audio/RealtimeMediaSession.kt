package com.colonelpanic.eva.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class RealtimeMediaConfig(
    val iceGatheringTimeoutMillis: Long = 10_000,
    val disconnectedGraceMillis: Long = 8_000,
    val speakerphone: Boolean = true,
)

sealed interface MediaFailure {
    val message: String

    data object MicrophonePermissionRequired : MediaFailure {
        override val message = "Microphone permission is required before voice can start."
    }

    data object AudioFocusDenied : MediaFailure {
        override val message = "Another app holds audio. Try again when it finishes."
    }

    data object IceGatheringTimeout : MediaFailure {
        override val message = "Network setup for voice timed out."
    }

    data object PeerFailed : MediaFailure {
        override val message = "The voice connection failed."
    }

    data object Disconnected : MediaFailure {
        override val message = "The voice connection stayed disconnected."
    }

    data class Rejected(
        override val message: String,
    ) : MediaFailure
}

sealed interface RealtimeMediaState {
    data object Idle : RealtimeMediaState

    data object Preparing : RealtimeMediaState

    data class OfferReady(
        val sdp: String,
    ) : RealtimeMediaState

    data object Connecting : RealtimeMediaState

    /** Transport connected; [remoteAudio] is true once a remote audio track has arrived. */
    data class Connected(
        val remoteAudio: Boolean,
    ) : RealtimeMediaState

    data class Failed(
        val reason: MediaFailure,
    ) : RealtimeMediaState

    data object Closed : RealtimeMediaState
}

enum class AudioFocusState { NONE, HELD, TRANSIENT_LOSS, LOST }

data class MediaControls(
    val microphoneMuted: Boolean = false,
    val playbackMuted: Boolean = false,
    val focus: AudioFocusState = AudioFocusState.NONE,
)

/** Elapsed milliseconds since [createOffer] for the media milestones observed so far. */
data class MediaTimeline(
    val offerReadyMillis: Long? = null,
    val connectedMillis: Long? = null,
    val remoteAudioMillis: Long? = null,
)

class MediaException(
    val failure: MediaFailure,
) : IllegalStateException(failure.message)

/**
 * One realtime audio leg between this device and a provider. Signaling (offer and answer
 * transport) is owned by the caller; this session never opens network control connections.
 */
interface RealtimeMediaSession : AutoCloseable {
    val state: StateFlow<RealtimeMediaState>
    val controls: StateFlow<MediaControls>
    val timeline: StateFlow<MediaTimeline>

    /** Provider event messages received on the negotiated data channel. Completes when the session ends. */
    val events: Flow<String>

    /** True once the provider event channel is open and [send] can succeed. */
    val eventsReady: StateFlow<Boolean>

    /** Sends one provider event message. Throws if the channel is not open. */
    fun send(event: String)

    /** Idle → Preparing → OfferReady. Throws [MediaException] and moves to Failed on error. */
    suspend fun createOffer(): String

    /** OfferReady → Connecting; later events move to Connected or Failed. */
    suspend fun acceptAnswer(sdp: String)

    fun setMicrophoneMuted(muted: Boolean)

    fun setPlaybackMuted(muted: Boolean)

    /** Releases capture, focus, and the peer connection. Safe to call repeatedly. */
    override fun close()
}
