@file:OptIn(ExperimentalCoroutinesApi::class)

package com.colonelpanic.eva.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePeerLink : PeerLink {
    val channel = Channel<PeerEvent>(Channel.UNLIMITED)
    override val events = channel.receiveAsFlow()
    override val messages = emptyFlow<String>()

    override fun send(text: String) = true

    var sdp: String? = null
    var remoteAnswer: String? = null
    var microphoneEnabled: Boolean? = null
    var playbackEnabled: Boolean? = null
    var closeCount = 0
    var failCreate = false

    override suspend fun createLocalOffer() {
        if (failCreate) throw IllegalStateException("codec mismatch")
        sdp = "v=0 offer"
    }

    override fun localDescription() = sdp

    override suspend fun setRemoteAnswer(sdp: String) {
        remoteAnswer = sdp
    }

    override fun setMicrophoneEnabled(enabled: Boolean) {
        microphoneEnabled = enabled
    }

    override fun setPlaybackEnabled(enabled: Boolean) {
        playbackEnabled = enabled
    }

    override fun close() {
        closeCount++
    }

    fun gatheringComplete() {
        sdp = "$sdp a=candidate"
        channel.trySend(PeerEvent.IceGatheringComplete)
    }

    fun connection(state: PeerConnectionState) = channel.trySend(PeerEvent.ConnectionChanged(state))
}

private class FakeRoute(
    private val grant: Boolean = true,
) : AudioRoutePort {
    var acquired = 0
    var released = 0
    var onFocusChange: ((AudioFocusState) -> Unit)? = null
    var duringAcquire: () -> Unit = {}

    override fun acquire(
        speakerphone: Boolean,
        onFocusChange: (AudioFocusState) -> Unit,
    ): Boolean {
        acquired++
        this.onFocusChange = onFocusChange
        duringAcquire()
        return grant
    }

    override fun release() {
        released++
    }
}

private class Harness(
    scope: TestScope,
    granted: Boolean = true,
    focus: Boolean = true,
) {
    val link = FakePeerLink()
    val route = FakeRoute(focus)
    val cues = mutableListOf<VoiceCue>()
    var opened = 0
    var duringOpen: () -> Unit = {}
    val controller =
        RealtimeMediaController(
            config = RealtimeMediaConfig(iceGatheringTimeoutMillis = 10_000, disconnectedGraceMillis = 8_000),
            peerFactory = {
                opened++
                duringOpen()
                link
            },
            route = route,
            microphoneGranted = { granted },
            parentScope = CoroutineScope(scope.backgroundScope.coroutineContext),
            cues = { cues += it },
            nowMillis = { scope.testScheduler.currentTime },
        )

    suspend fun offer(scope: TestScope): String {
        val offer = scope.async { controller.createOffer() }
        scope.runCurrent()
        assertEquals(RealtimeMediaState.Preparing, controller.state.value)
        scope.advanceTimeBy(250)
        link.gatheringComplete()
        return offer.await()
    }
}

class RealtimeMediaControllerTest {
    @Test
    fun `live capture without permission fails before any audio or peer resource is touched`() =
        runTest {
            val harness = Harness(this, granted = false)
            val error = runCatching { harness.controller.createOffer() }.exceptionOrNull()
            assertEquals(MediaFailure.MicrophonePermissionRequired, (error as MediaException).failure)
            assertEquals(RealtimeMediaState.Failed(MediaFailure.MicrophonePermissionRequired), harness.controller.state.value)
            assertEquals(0, harness.route.acquired)
            assertEquals(0, harness.opened)
        }

    @Test
    fun `denied focus releases nothing it did not take`() =
        runTest {
            val harness = Harness(this, focus = false)
            val error = runCatching { harness.controller.createOffer() }.exceptionOrNull()
            assertEquals(MediaFailure.AudioFocusDenied, (error as MediaException).failure)
            assertEquals(1, harness.route.acquired)
            assertEquals(0, harness.opened)
            assertEquals(0, harness.route.released)
        }

    @Test
    fun `offer waits for gathered candidates and the answer leads to connected media`() =
        runTest {
            val harness = Harness(this)
            val sdp = harness.offer(this)
            assertEquals("v=0 offer a=candidate", sdp)
            assertEquals(RealtimeMediaState.OfferReady(sdp), harness.controller.state.value)
            assertEquals(250L, harness.controller.timeline.value.offerReadyMillis)
            assertEquals(true, harness.link.microphoneEnabled)

            harness.controller.acceptAnswer("v=0 answer")
            assertEquals("v=0 answer", harness.link.remoteAnswer)
            assertEquals(RealtimeMediaState.Connecting, harness.controller.state.value)
            harness.link.connection(PeerConnectionState.CONNECTED)
            runCurrent()
            assertEquals(RealtimeMediaState.Connected(remoteAudio = false), harness.controller.state.value)
            harness.link.channel.trySend(PeerEvent.RemoteAudioTrack)
            runCurrent()
            assertEquals(RealtimeMediaState.Connected(remoteAudio = true), harness.controller.state.value)
            assertEquals(true, harness.link.playbackEnabled)

            harness.controller.close()
            harness.controller.close()
            assertEquals(RealtimeMediaState.Closed, harness.controller.state.value)
            assertEquals(1, harness.link.closeCount)
            assertEquals(1, harness.route.released)
            assertEquals(AudioFocusState.NONE, harness.controller.controls.value.focus)
        }

    @Test
    fun `the microphone going live and the session ending are each announced once`() =
        runTest {
            val harness = Harness(this)
            harness.offer(this)
            harness.controller.acceptAnswer("answer")
            assertEquals(emptyList<VoiceCue>(), harness.cues)

            harness.link.connection(PeerConnectionState.CONNECTED)
            runCurrent()
            assertEquals(listOf(VoiceCue.Started), harness.cues)

            // A drop inside the grace period is the same session, so it must not chime again.
            harness.link.connection(PeerConnectionState.DISCONNECTED)
            runCurrent()
            advanceTimeBy(3_000)
            harness.link.connection(PeerConnectionState.CONNECTED)
            runCurrent()
            assertEquals(listOf(VoiceCue.Started), harness.cues)

            harness.controller.close()
            harness.controller.close()
            assertEquals(listOf(VoiceCue.Started, VoiceCue.Ended), harness.cues)
        }

    @Test
    fun `a session that fails before the microphone goes live stays silent`() =
        runTest {
            val harness = Harness(this)
            harness.offer(this)
            harness.controller.acceptAnswer("answer")
            harness.link.connection(PeerConnectionState.FAILED)
            runCurrent()
            assertEquals(RealtimeMediaState.Failed(MediaFailure.PeerFailed), harness.controller.state.value)
            assertEquals(emptyList<VoiceCue>(), harness.cues)
        }

    @Test
    fun `ice gathering timeout fails the offer and releases capture and focus`() =
        runTest {
            val harness = Harness(this)
            val offer = async { runCatching { harness.controller.createOffer() } }
            runCurrent()
            advanceTimeBy(10_001)
            runCurrent()
            val error = offer.await().exceptionOrNull()
            assertEquals(MediaFailure.IceGatheringTimeout, (error as MediaException).failure)
            assertEquals(1, harness.link.closeCount)
            assertEquals(1, harness.route.released)
            val late = runCatching { harness.controller.acceptAnswer("late") }.exceptionOrNull()
            assertEquals(MediaFailure.IceGatheringTimeout, (late as MediaException).failure)
        }

    @Test
    fun `peer errors during setup surface their reason and second offers are rejected`() =
        runTest {
            val harness = Harness(this)
            harness.link.failCreate = true
            val error = runCatching { harness.controller.createOffer() }.exceptionOrNull()
            assertEquals(MediaFailure.Rejected("codec mismatch"), (error as MediaException).failure)
            val again = runCatching { harness.controller.createOffer() }.exceptionOrNull()
            assertTrue(again is IllegalStateException && again !is MediaException)
            assertEquals(1, harness.link.closeCount)
        }

    @Test
    fun `brief disconnection recovers but a long one fails after the grace period`() =
        runTest {
            val harness = Harness(this)
            harness.offer(this)
            harness.controller.acceptAnswer("answer")
            harness.link.connection(PeerConnectionState.CONNECTED)
            harness.link.connection(PeerConnectionState.DISCONNECTED)
            runCurrent()
            advanceTimeBy(3_000)
            harness.link.connection(PeerConnectionState.CONNECTED)
            runCurrent()
            advanceTimeBy(6_000)
            assertEquals(RealtimeMediaState.Connected(remoteAudio = false), harness.controller.state.value)

            harness.link.connection(PeerConnectionState.DISCONNECTED)
            runCurrent()
            advanceTimeBy(8_001)
            assertEquals(RealtimeMediaState.Failed(MediaFailure.Disconnected), harness.controller.state.value)
            assertEquals(1, harness.link.closeCount)
            harness.controller.close()
            assertEquals(RealtimeMediaState.Failed(MediaFailure.Disconnected), harness.controller.state.value)
            assertEquals(1, harness.route.released)
        }

    @Test
    fun `peer failure is terminal and closes resources once`() =
        runTest {
            val harness = Harness(this)
            harness.offer(this)
            harness.controller.acceptAnswer("answer")
            harness.link.connection(PeerConnectionState.FAILED)
            runCurrent()
            assertEquals(RealtimeMediaState.Failed(MediaFailure.PeerFailed), harness.controller.state.value)
            assertEquals(1, harness.link.closeCount)
            assertEquals(1, harness.route.released)
        }

    @Test
    fun `mute and focus loss disable tracks without changing the user's choices`() =
        runTest {
            val harness = Harness(this)
            harness.offer(this)
            harness.controller.setMicrophoneMuted(true)
            assertEquals(false, harness.link.microphoneEnabled)
            assertEquals(true, harness.link.playbackEnabled)

            checkNotNull(harness.route.onFocusChange)(AudioFocusState.LOST)
            assertEquals(false, harness.link.playbackEnabled)
            assertEquals(AudioFocusState.LOST, harness.controller.controls.value.focus)
            assertTrue(harness.controller.controls.value.microphoneMuted)
            assertFalse(harness.controller.controls.value.playbackMuted)

            checkNotNull(harness.route.onFocusChange)(AudioFocusState.HELD)
            assertEquals(false, harness.link.microphoneEnabled)
            assertEquals(true, harness.link.playbackEnabled)

            harness.controller.setMicrophoneMuted(false)
            assertEquals(true, harness.link.microphoneEnabled)
            harness.controller.close()
            checkNotNull(harness.route.onFocusChange)(AudioFocusState.LOST)
            assertEquals(AudioFocusState.NONE, harness.controller.controls.value.focus)
        }

    @Test
    fun `closing while the peer is being opened disposes the late peer and releases focus once`() =
        runTest {
            val harness = Harness(this)
            harness.duringOpen = { harness.controller.close() }
            val error = runCatching { harness.controller.createOffer() }.exceptionOrNull()
            assertEquals(MediaFailure.Rejected("Session closed."), (error as MediaException).failure)
            assertEquals(RealtimeMediaState.Closed, harness.controller.state.value)
            assertEquals(1, harness.opened)
            assertEquals(1, harness.link.closeCount)
            assertEquals(1, harness.route.released)
            assertEquals(null, harness.link.microphoneEnabled)
            assertEquals(AudioFocusState.NONE, harness.controller.controls.value.focus)
        }

    @Test
    fun `closing while focus is being requested hands focus back and never opens a peer`() =
        runTest {
            val harness = Harness(this)
            harness.route.duringAcquire = { harness.controller.close() }
            val error = runCatching { harness.controller.createOffer() }.exceptionOrNull()
            assertTrue(error is MediaException)
            assertEquals(RealtimeMediaState.Closed, harness.controller.state.value)
            assertEquals(1, harness.route.acquired)
            assertEquals(1, harness.route.released)
            assertEquals(0, harness.opened)
        }

    @Test
    fun `an unexpected setup exception still releases focus and reports a failure`() =
        runTest {
            val harness = Harness(this)
            harness.duringOpen = { throw IllegalStateException("native init failed") }
            val error = runCatching { harness.controller.createOffer() }.exceptionOrNull()
            assertEquals(MediaFailure.Rejected("native init failed"), (error as MediaException).failure)
            assertEquals(RealtimeMediaState.Failed(MediaFailure.Rejected("native init failed")), harness.controller.state.value)
            assertEquals(1, harness.route.released)
            assertEquals(0, harness.link.closeCount)
        }
}
