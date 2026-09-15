package com.colonelpanic.eva.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Session state machine over a [PeerLink] and an [AudioRoutePort]. Peer events may arrive on any
 * thread; state flows are the only shared mutable surface.
 */
class RealtimeMediaController internal constructor(
    private val config: RealtimeMediaConfig,
    private val peerFactory: PeerLinkFactory,
    private val route: AudioRoutePort,
    private val microphoneGranted: () -> Boolean,
    parentScope: CoroutineScope,
    private val cues: VoiceCuePlayer = VoiceCuePlayer {},
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : RealtimeMediaSession {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val signaling = Mutex()
    private val released = AtomicBoolean(false)

    /** True between the cues, so a reconnection inside the grace period does not chime again. */
    private val cued = AtomicBoolean(false)
    private val resources = Any()
    private var routeHeld = false
    private val mutableState = MutableStateFlow<RealtimeMediaState>(RealtimeMediaState.Idle)
    private val mutableControls = MutableStateFlow(MediaControls())
    private val mutableTimeline = MutableStateFlow(MediaTimeline())

    @Volatile
    private var link: PeerLink? = null
    private var eventsJob: Job? = null

    @Volatile
    private var graceJob: Job? = null

    @Volatile
    private var remoteAudio = false
    private var startedAt = 0L

    override val state = mutableState.asStateFlow()
    override val controls = mutableControls.asStateFlow()
    override val timeline = mutableTimeline.asStateFlow()
    private val messageChannel = Channel<String>(Channel.UNLIMITED)
    override val events = messageChannel.receiveAsFlow()
    private val mutableEventsReady = MutableStateFlow(false)
    override val eventsReady = mutableEventsReady.asStateFlow()
    private var messagesJob: Job? = null

    override fun send(event: String) {
        val sent = link?.send(event) ?: false
        check(sent) { "The provider event channel is not open." }
    }

    override suspend fun createOffer(): String =
        signaling.withLock {
            check(mutableState.value == RealtimeMediaState.Idle) { "createOffer requires an idle session" }
            if (!microphoneGranted()) throw fail(MediaFailure.MicrophonePermissionRequired)
            mutableState.value = RealtimeMediaState.Preparing
            startedAt = nowMillis()
            try {
                acquireRoute()
                val opened = openPeer()
                val gathered = CompletableDeferred<Unit>()
                publish(opened, gathered)
                try {
                    withTimeout(config.iceGatheringTimeoutMillis) {
                        opened.createLocalOffer()
                        gathered.await()
                    }
                } catch (_: TimeoutCancellationException) {
                    throw fail(MediaFailure.IceGatheringTimeout)
                } catch (error: MediaException) {
                    throw fail(error.failure)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    throw fail(MediaFailure.Rejected(error.message ?: "Offer creation failed."))
                }
                val sdp = opened.localDescription() ?: throw fail(MediaFailure.Rejected("No local description was produced."))
                if (released.get()) throw closedException()
                mutableTimeline.update { it.copy(offerReadyMillis = elapsed()) }
                mutableState.value = RealtimeMediaState.OfferReady(sdp)
                sdp
            } catch (error: Throwable) {
                releaseResources()
                if (error is MediaException || error is CancellationException) throw error
                throw fail(MediaFailure.Rejected(error.message ?: "Voice setup failed."))
            }
        }

    /** Focus is handed back immediately if [close] won the race while the request was in flight. */
    private fun acquireRoute() {
        val granted = route.acquire(config.speakerphone, ::onFocusChange)
        synchronized(resources) {
            if (released.get()) {
                if (granted) route.release()
                throw closedException()
            }
            if (!granted) throw fail(MediaFailure.AudioFocusDenied)
            routeHeld = true
        }
        mutableControls.update { it.copy(focus = AudioFocusState.HELD) }
    }

    private fun openPeer(): PeerLink {
        val opened =
            try {
                peerFactory.open()
            } catch (error: Exception) {
                throw fail(MediaFailure.Rejected(error.message ?: "The voice connection could not be created."))
            }
        return opened
    }

    /** Publishes the peer and starts its event collection atomically with respect to [close]. */
    private fun publish(
        opened: PeerLink,
        gathered: CompletableDeferred<Unit>,
    ) {
        synchronized(resources) {
            if (released.get()) {
                opened.close()
                throw closedException()
            }
            link = opened
            eventsJob = scope.launch { opened.events.collect { onPeerEvent(it, gathered) } }
            messagesJob = scope.launch { opened.messages.collect { messageChannel.send(it) } }
        }
        applyControls()
    }

    private fun closedException(): MediaException {
        val current = mutableState.value
        return MediaException((current as? RealtimeMediaState.Failed)?.reason ?: MediaFailure.Rejected(SESSION_CLOSED))
    }

    override suspend fun acceptAnswer(sdp: String) {
        signaling.withLock {
            when (val current = mutableState.value) {
                is RealtimeMediaState.OfferReady -> Unit
                is RealtimeMediaState.Failed -> throw MediaException(current.reason)
                RealtimeMediaState.Closed -> throw MediaException(MediaFailure.Rejected(SESSION_CLOSED))
                else -> error("acceptAnswer requires a ready offer")
            }
            val opened = checkNotNull(link)
            mutableState.value = RealtimeMediaState.Connecting
            try {
                opened.setRemoteAnswer(sdp)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw fail(MediaFailure.Rejected(error.message ?: "The provider answer was rejected."))
            }
        }
    }

    override fun setMicrophoneMuted(muted: Boolean) {
        mutableControls.update { it.copy(microphoneMuted = muted) }
        applyControls()
    }

    override fun setPlaybackMuted(muted: Boolean) {
        mutableControls.update { it.copy(playbackMuted = muted) }
        applyControls()
    }

    override fun close() {
        releaseResources()
        mutableState.update { if (it is RealtimeMediaState.Failed) it else RealtimeMediaState.Closed }
    }

    private fun fail(reason: MediaFailure): MediaException {
        releaseResources()
        mutableState.update {
            if (it is RealtimeMediaState.Failed ||
                it == RealtimeMediaState.Closed
            ) {
                it
            } else {
                RealtimeMediaState.Failed(reason)
            }
        }
        return MediaException(reason)
    }

    private fun releaseResources() {
        if (!released.compareAndSet(false, true)) return
        val peer: PeerLink?
        val heldRoute: Boolean
        synchronized(resources) {
            graceJob?.cancel()
            eventsJob?.cancel()
            messagesJob?.cancel()
            peer = link
            link = null
            heldRoute = routeHeld
            routeHeld = false
        }
        peer?.close()
        if (heldRoute) route.release()
        mutableEventsReady.value = false
        messageChannel.close()
        mutableControls.update { it.copy(focus = AudioFocusState.NONE) }
        scope.cancel()
        // Only a session the user was told about is worth closing out loud, and the route is
        // already back to normal here, so the cue is not cut short by the mode change.
        if (cued.compareAndSet(true, false)) cues.play(VoiceCue.Ended)
    }

    private fun onPeerEvent(
        event: PeerEvent,
        gathered: CompletableDeferred<Unit>,
    ) {
        when (event) {
            PeerEvent.IceGatheringComplete -> {
                gathered.complete(Unit)
            }

            is PeerEvent.Error -> {
                val failure = MediaFailure.Rejected(event.message)
                if (!gathered.completeExceptionally(MediaException(failure))) fail(failure)
            }

            PeerEvent.EventsChannelOpen -> {
                mutableEventsReady.value = true
            }

            PeerEvent.RemoteAudioTrack -> {
                remoteAudio = true
                mutableTimeline.update { it.copy(remoteAudioMillis = it.remoteAudioMillis ?: elapsed()) }
                mutableState.update { if (it is RealtimeMediaState.Connected) it.copy(remoteAudio = true) else it }
                applyControls()
            }

            is PeerEvent.ConnectionChanged -> {
                onConnectionChanged(event.state)
            }
        }
    }

    private fun onConnectionChanged(peer: PeerConnectionState) {
        when (peer) {
            PeerConnectionState.CONNECTED -> {
                graceJob?.cancel()
                graceJob = null
                mutableTimeline.update { it.copy(connectedMillis = it.connectedMillis ?: elapsed()) }
                mutableState.update {
                    if (it is RealtimeMediaState.Connecting ||
                        it is RealtimeMediaState.Connected
                    ) {
                        RealtimeMediaState.Connected(remoteAudio)
                    } else {
                        it
                    }
                }
                if (mutableState.value is RealtimeMediaState.Connected && cued.compareAndSet(false, true)) {
                    cues.play(VoiceCue.Started)
                }
            }

            PeerConnectionState.DISCONNECTED -> {
                if (graceJob == null && !released.get()) {
                    graceJob =
                        scope.launch {
                            delay(config.disconnectedGraceMillis)
                            fail(MediaFailure.Disconnected)
                        }
                }
            }

            PeerConnectionState.FAILED -> {
                fail(MediaFailure.PeerFailed)
            }

            PeerConnectionState.CLOSED -> {
                if (!released.get()) fail(MediaFailure.Disconnected)
            }

            PeerConnectionState.NEW, PeerConnectionState.CONNECTING -> {}
        }
    }

    private fun onFocusChange(focus: AudioFocusState) {
        if (released.get()) return
        mutableControls.update { it.copy(focus = focus) }
        applyControls()
    }

    private fun applyControls() {
        val current = mutableControls.value
        val focused = current.focus == AudioFocusState.HELD
        link?.setMicrophoneEnabled(focused && !current.microphoneMuted)
        link?.setPlaybackEnabled(focused && !current.playbackMuted)
    }

    private fun elapsed() = nowMillis() - startedAt

    private companion object {
        const val SESSION_CLOSED = "Session closed."
    }
}
