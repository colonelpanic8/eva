package com.colonelpanic.eva.adapters.android

import android.media.AudioDeviceInfo
import com.colonelpanic.eva.call.CallLeg
import com.colonelpanic.eva.call.CallMedia
import com.colonelpanic.eva.call.LegEvent
import com.colonelpanic.eva.call.LegObservation
import com.colonelpanic.eva.call.LegState
import com.colonelpanic.eva.call.LegStateTracker
import com.colonelpanic.eva.call.PacedUplink
import com.colonelpanic.eva.call.PcmFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One call's observation, keypad and hang-up, which a transport supplies to its [EngineCallLeg].
 *
 * Observation is polled, even for transports whose SDK pushes events (Twilio): an event-driven
 * route simply returns its latest observation. One polling loop keeps a single state machine and a
 * single "lost sight of the call" rule for every route, at the cost of up to [EngineCallLeg]'s poll
 * interval of latency on state changes, which nothing in a call task is sensitive to.
 */
internal interface CallRoute {
    /** Polled throughout the call; throwing means the provider lost sight of it. */
    suspend fun observe(): LegObservation

    /** Recovery after losing observation may use a fresh idle snapshot after acknowledged hang-up. */
    suspend fun confirmEnded(): Boolean = observe() is LegObservation.Gone

    /** What observation goes through, such as EVA's call-audio helper, named when it fails. */
    val observer: String

    /** What the owner should check when the call is lost from sight. */
    val lostCheck: String

    /** [inBand] plays keys as tones in the uplink and returns once they have played. */
    suspend fun sendDtmf(
        digits: String,
        inBand: suspend (String) -> Unit,
    ): String

    suspend fun hangUp()
}

/**
 * Where a call's audio meets EVA, at [EngineCallLeg.SAMPLE_RATE] PCM16 mono little-endian: the
 * Shizuku helper's redirected call audio ([HelperAudioPath]) or an in-app SDK ([InAppAudioPath]).
 *
 * This is the only place the two media families differ once a call is up. The differences that
 * matter to the shared leg are made explicit as properties instead of being inferred from the
 * route: whether the owner already hears the other party, and whether EVA's own local playback
 * leaks back into what is captured from the call.
 *
 * Both directions are blocking calls on threads the leg owns, and both are paced in real time by
 * something: [readRemote] by the platform socket (helper) or by the path's own clock (in-app), and
 * [writeUplink] by [PacedUplink]'s 20 ms clock in the leg. The model brain never paces audio itself.
 */
internal interface CallAudioPath {
    /** Whether the owner hears the other party without EVA playing them, as on a cellular call. */
    val ownerHearsRemote: Boolean

    /** Whether capture can contain EVA's own local playback, which listen-in must then remove. */
    val capturesLocalPlayback: Boolean

    /** Opens call audio, once, when the call is first live. */
    fun open()

    /** Fills [frame] with the next stretch of the other party, blocking until it is due; false once audio has ended. */
    fun readRemote(frame: ByteArray): Boolean

    /** Sends one paced uplink frame; false once the path is closed. */
    fun writeUplink(frame: ByteArray): Boolean

    /** The floor's routing: whether the owner's microphone reaches the other party. Called before and after [open]. */
    fun routeOwnerMicrophone(toRemote: Boolean)

    fun close()
}

/**
 * The leg of every Android call provider. It polls its [route] into the leg's state machine, opens
 * [audio] when the call is first live, delivers the other party's frames, paces queued agent audio
 * and in-band tones into the uplink at real time, applies the floor's microphone routing, and plays
 * the other party locally when the owner would not otherwise hear them. Listening in plays the
 * uplink locally and, when capture can hear that playback, silences the agent's input while it may
 * echo ([CallMonitorPlayback]). When the call ends audio and playback are released.
 *
 * Shared for every route because these are task semantics, not media plumbing: the coordinator
 * and the owner's call card must see the same leg states, the agent's speech must leave at the
 * same real-time pace (the brain delivers it in bursts), and "owner takes the floor", "listen in"
 * and "hang up" must mean the same thing whichever way the call was placed.
 *
 * Audio opens at the first Live observation, which is ringing for most routes rather than answer:
 * early media (ringback, carrier announcements, an IVR that answers before the network reports it)
 * should reach the agent, and the uplink must already be running when the other party first speaks.
 *
 * Verification: the helper path is device-verified for cellular calls on the owner's Pixel; the
 * in-app path is JVM-tested (state, remote frames, agent uplink, owner-microphone mixing, hang-up)
 * and verified in connected emulator calls against the Twilio test hotline. WhatsApp redirection
 * still needs a registered far-end account for device verification.
 */
internal class EngineCallLeg(
    override val id: String,
    placing: Boolean,
    private val route: CallRoute,
    private val audio: CallAudioPath,
    playbackDevice: (() -> AudioDeviceInfo?)? = null,
    // Debug-only demo recorder (see CallAudioCapture); null unless a demo asked for it.
    private val capture: CallAudioCapture? = null,
    private val admission: CallAdmission.Lease,
) : CallLeg,
    CallMedia.LocalPcm {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tracker = LegStateTracker(placing)
    override val state: StateFlow<LegState> = tracker.state
    override val events: Flow<LegEvent> = emptyFlow()
    override val media: CallMedia = this
    override val sampleRateHz = SAMPLE_RATE

    private val uplink = PacedUplink(SAMPLE_RATE)
    private val opened = MutableStateFlow(false)

    // Listen-in: the agent's side (what EVA sends), played locally when the owner asks to monitor.
    private val monitor = CallMonitorPlayback(SAMPLE_RATE, playbackDevice)

    // The other party, played locally for routes where the owner would otherwise hear nothing: a VoIP
    // redirection silences the app's own playback, and an in-app call has no playback but EVA's.
    private val remote = CallMonitorPlayback(SAMPLE_RATE, playbackDevice, listenIn = false).takeUnless { audio.ownerHearsRemote }

    init {
        scope.launch { track() }
    }

    override val remoteFrames: Flow<PcmFrame> =
        flow {
            opened.first { it }
            val bytes = ByteArray(uplink.frameSamples * 2)
            while (audio.readRemote(bytes)) {
                // Only where capture can contain EVA's own playback: on a path that cannot, the gate would
                // mute the other party while listen-in plays for nothing. Where it can, the gate engages only
                // while listen-in plays; with it off (the default) the agent hears capture untouched.
                if (audio.capturesLocalPlayback) monitor.clean(bytes)
                capture?.downlink(bytes)
                remote?.write(bytes)
                emit(
                    PcmFrame(
                        ShortArray(uplink.frameSamples) { (bytes[2 * it].toInt() and 0xff or (bytes[2 * it + 1].toInt() shl 8)).toShort() },
                        SAMPLE_RATE,
                    ),
                )
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun sendAgentFrame(frame: PcmFrame) = uplink.enqueue(frame)

    override suspend fun cancelAgentAudio() = uplink.cancel()

    // Before audio opens nothing is released, so there is nothing anyone could hear to wait for.
    override suspend fun awaitAgentAudioPlayed() {
        if (opened.value) uplink.awaitDrained { tracker.ended }
    }

    override suspend fun sendDtmf(digits: String): String =
        route.sendDtmf(digits) { uplink.playAndAwait(PacedUplink.dtmfTones(it, SAMPLE_RATE)) }

    /** Records the request first, so whatever end the route then observes is attributed to EVA's hang-up. */
    override suspend fun hangUp() {
        tracker.hangUpRequested = true
        route.hangUp()
    }

    override suspend fun setOwnerMicrophone(toRemote: Boolean) = withContext(Dispatchers.IO) { audio.routeOwnerMicrophone(toRemote) }

    /** The owner already hears the other party (natively or through [remote]); monitoring plays the agent's side locally. */
    override suspend fun setOwnerMonitoring(enabled: Boolean) = monitor.setEnabled(enabled)

    /**
     * The leg's lifetime: poll until the call ends or the route throws (EVA lost sight of it, which ends
     * the leg FAILED with an explicitly unconfirmed end and the route's check for the owner), then
     * release audio and playback whatever happened.
     */
    private suspend fun track() {
        while (scope.isActive && !tracker.ended) {
            val observation =
                try {
                    route.observe().also { if (it is LegObservation.Live && !opened.value) openAudio() }
                } catch (error: Exception) {
                    // The model cannot supervise a call whose media/control disappeared. Ask its
                    // route to disconnect (Telecom can survive a dead audio helper), but never turn
                    // that request into a claim that the call ended. Keep admission reserved until
                    // a later observation confirms Gone, even after this leg releases its media.
                    val hangUp = runCatching { route.hangUp() }
                    val detail =
                        if (hangUp.isSuccess) {
                            "Hang-up requested; its completion is unconfirmed."
                        } else {
                            "Hang-up also failed: ${hangUp.exceptionOrNull()?.message}."
                        }
                    admission.observationLost(route)
                    tracker.lost("${route.observer} (${error.message ?: error::class.simpleName})", "$detail ${route.lostCheck}")
                    break
                }
            tracker.observe(observation)
            if (!tracker.ended) delay(POLL_MILLIS)
        }
        runCatching { audio.close() }
        monitor.release()
        remote?.release()
        capture?.close()
        if ((state.value as? LegState.Ended)?.endConfirmed == true) admission.release()
        scope.cancel()
    }

    /** Opens the path, starts local playback of the other party, and starts the real-time uplink clock. */
    private fun openAudio() {
        audio.open()
        remote?.setEnabled(true)
        opened.value = true
        val bytes = ByteArray(uplink.frameSamples * 2)
        scope.launch {
            uplink.run { frame ->
                frame.forEachIndexed { i, sample ->
                    bytes[2 * i] = sample.toByte()
                    bytes[2 * i + 1] = (sample.toInt() shr 8).toByte()
                }
                audio.writeUplink(bytes).also {
                    if (it) {
                        monitor.write(bytes)
                        capture?.uplink(bytes)
                    }
                }
            }
        }
    }

    companion object {
        // The model brain's native rate, so agent audio needs no resampling on the way out. The helper
        // captures and injects at it, and SDK audio devices are configured for it.
        const val SAMPLE_RATE = 24_000

        // Fast enough that ringing, answer and hang-up show on the card without a noticeable lag.
        private const val POLL_MILLIS = 300L
    }
}
