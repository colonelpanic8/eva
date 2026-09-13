package com.colonelpanic.eva.audio.webrtc

import com.colonelpanic.eva.audio.MicrophoneMode
import com.colonelpanic.eva.audio.PeerConnectionState
import com.colonelpanic.eva.audio.PeerEvent
import com.colonelpanic.eva.audio.PeerLink
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * org.webrtc implementation of [PeerLink]. Media order matches the validated browser POC: local
 * audio leg, then the `oai-events` data channel, then the offer. Callbacks arrive on WebRTC threads
 * and are forwarded through a buffered channel.
 */
internal class WebRtcPeerLink(
    factory: PeerConnectionFactory,
    microphone: MicrophoneMode,
) : PeerLink {
    private val eventChannel = Channel<PeerEvent>(Channel.BUFFERED)
    override val events = eventChannel.receiveAsFlow()
    private val messageChannel = Channel<String>(Channel.UNLIMITED)
    override val messages = messageChannel.receiveAsFlow()
    private val closed = AtomicBoolean(false)
    private val native = Any()

    @Volatile
    private var microphoneEnabled = true

    @Volatile
    private var playbackEnabled = true

    @Volatile
    private var remoteTrack: AudioTrack? = null
    private var source: AudioSource? = null
    private var localTrack: AudioTrack? = null

    private val observer =
        object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) emit(PeerEvent.IceGatheringComplete)
            }

            override fun onIceCandidate(candidate: IceCandidate) = Unit

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

            override fun onAddStream(stream: MediaStream) = Unit

            override fun onRemoveStream(stream: MediaStream) = Unit

            override fun onDataChannel(channel: DataChannel) = Unit

            override fun onRenegotiationNeeded() = Unit

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                emit(
                    PeerEvent.ConnectionChanged(
                        when (newState) {
                            PeerConnection.PeerConnectionState.NEW -> PeerConnectionState.NEW
                            PeerConnection.PeerConnectionState.CONNECTING -> PeerConnectionState.CONNECTING
                            PeerConnection.PeerConnectionState.CONNECTED -> PeerConnectionState.CONNECTED
                            PeerConnection.PeerConnectionState.DISCONNECTED -> PeerConnectionState.DISCONNECTED
                            PeerConnection.PeerConnectionState.FAILED -> PeerConnectionState.FAILED
                            PeerConnection.PeerConnectionState.CLOSED -> PeerConnectionState.CLOSED
                        },
                    ),
                )
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                synchronized(native) {
                    if (closed.get()) return
                    val track = transceiver.receiver.track() as? AudioTrack ?: return
                    remoteTrack = track
                    track.setEnabled(playbackEnabled)
                }
                emit(PeerEvent.RemoteAudioTrack)
            }
        }

    private val connection: PeerConnection =
        factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()), observer)
            ?: throw IllegalStateException("The peer connection could not be created.")
    private val dataChannel: DataChannel

    init {
        when (microphone) {
            MicrophoneMode.LIVE -> {
                val audioSource = factory.createAudioSource(MediaConstraints())
                source = audioSource
                localTrack =
                    factory.createAudioTrack(LOCAL_TRACK_ID, audioSource).also {
                        it.setEnabled(microphoneEnabled)
                        connection.addTrack(it, listOf(STREAM_ID))
                    }
            }

            MicrophoneMode.NONE -> {
                connection.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
                )
            }
        }
        dataChannel = connection.createDataChannel(EVENTS_CHANNEL, DataChannel.Init())
        dataChannel.registerObserver(
            object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit

                override fun onStateChange() {
                    val open =
                        synchronized(native) {
                            !closed.get() && dataChannel.state() == DataChannel.State.OPEN
                        }
                    if (open) emit(PeerEvent.EventsChannelOpen)
                }

                override fun onMessage(buffer: DataChannel.Buffer) {
                    if (buffer.binary || closed.get()) return
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    messageChannel.trySend(String(bytes, Charsets.UTF_8))
                }
            },
        )
    }

    override fun send(text: String): Boolean =
        synchronized(native) {
            if (closed.get() || dataChannel.state() != DataChannel.State.OPEN) return false
            dataChannel.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false))
        }

    override suspend fun createLocalOffer() {
        val offer = createDescription { connection.createOffer(it, MediaConstraints()) }
        applyDescription { connection.setLocalDescription(it, offer) }
    }

    override fun localDescription(): String? = connection.localDescription?.description

    override suspend fun setRemoteAnswer(sdp: String) {
        applyDescription { connection.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, sdp)) }
    }

    override fun setMicrophoneEnabled(enabled: Boolean) {
        synchronized(native) {
            microphoneEnabled = enabled
            if (!closed.get()) localTrack?.setEnabled(enabled)
        }
    }

    override fun setPlaybackEnabled(enabled: Boolean) {
        synchronized(native) {
            playbackEnabled = enabled
            if (!closed.get()) remoteTrack?.setEnabled(enabled)
        }
    }

    /**
     * Detaches native handles under the lock, then disposes them outside it: disposal blocks on
     * WebRTC threads whose callbacks also take the lock.
     */
    override fun close() {
        val track: AudioTrack?
        val audioSource: AudioSource?
        synchronized(native) {
            if (!closed.compareAndSet(false, true)) return
            remoteTrack = null
            track = localTrack
            audioSource = source
            localTrack = null
            source = null
        }
        eventChannel.close()
        messageChannel.close()
        dataChannel.unregisterObserver()
        dataChannel.close()
        dataChannel.dispose()
        connection.close()
        connection.dispose()
        track?.dispose()
        audioSource?.dispose()
    }

    private fun emit(event: PeerEvent) {
        if (!closed.get()) eventChannel.trySend(event)
    }

    private suspend fun createDescription(start: (SdpObserver) -> Unit): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            start(
                object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription) {
                        continuation.resume(description)
                    }

                    override fun onSetSuccess() = Unit

                    override fun onCreateFailure(error: String?) {
                        continuation.resumeWithException(IllegalStateException(error ?: "Offer creation failed."))
                    }

                    override fun onSetFailure(error: String?) = Unit
                },
            )
        }

    private suspend fun applyDescription(start: (SdpObserver) -> Unit) {
        suspendCancellableCoroutine { continuation ->
            start(
                object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription) = Unit

                    override fun onSetSuccess() {
                        continuation.resume(Unit)
                    }

                    override fun onCreateFailure(error: String?) = Unit

                    override fun onSetFailure(error: String?) {
                        continuation.resumeWithException(IllegalStateException(error ?: "Session description was rejected."))
                    }
                },
            )
        }
    }

    private companion object {
        const val LOCAL_TRACK_ID = "eva-audio"
        const val STREAM_ID = "eva"
        const val EVENTS_CHANNEL = "oai-events"
    }
}
