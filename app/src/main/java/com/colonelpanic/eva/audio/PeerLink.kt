package com.colonelpanic.eva.audio

import kotlinx.coroutines.flow.Flow

internal enum class PeerConnectionState { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

internal sealed interface PeerEvent {
    data object IceGatheringComplete : PeerEvent

    data class ConnectionChanged(
        val state: PeerConnectionState,
    ) : PeerEvent

    data object RemoteAudioTrack : PeerEvent

    /** The `oai-events` data channel is open; provider events may now be exchanged. */
    data object EventsChannelOpen : PeerEvent

    data class Error(
        val message: String,
    ) : PeerEvent
}

/** One peer connection with its local audio leg and event channel already attached. */
internal interface PeerLink {
    val events: Flow<PeerEvent>

    /** Text messages received on the provider event channel, in arrival order. */
    val messages: Flow<String>

    /** Sends one text message on the provider event channel; false if it is not open. */
    fun send(text: String): Boolean

    /** Creates and applies the local offer; ICE gathering completes asynchronously via [events]. */
    suspend fun createLocalOffer()

    /** The applied local description, including gathered candidates once gathering is complete. */
    fun localDescription(): String?

    suspend fun setRemoteAnswer(sdp: String)

    fun setMicrophoneEnabled(enabled: Boolean)

    fun setPlaybackEnabled(enabled: Boolean)

    fun close()
}

internal fun interface PeerLinkFactory {
    fun open(microphone: MicrophoneMode): PeerLink
}

/** Audio focus, mode, and routing owned for the lifetime of one session. */
internal interface AudioRoutePort {
    /** Returns false when focus is denied. [onFocusChange] may be invoked from any thread. */
    fun acquire(
        speakerphone: Boolean,
        onFocusChange: (AudioFocusState) -> Unit,
    ): Boolean

    fun release()
}
