package com.colonelpanic.eva.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colonelpanic.eva.audio.webrtc.WebRtcMediaSessionFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves local media negotiation only: the offer is built without any provider or network peer.
 * Synthetic or absent emulator audio is not evidence of physical microphone or speaker behavior.
 */
@RunWith(AndroidJUnit4::class)
class WebRtcNegotiationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val factory = WebRtcMediaSessionFactory(instrumentation.targetContext)

    @Before
    fun grantMicrophone() {
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, MicrophonePermission.PERMISSION)
    }

    @Test
    fun offerCarriesAudioAndEventChannel() {
        val session = factory.create(RealtimeMediaConfig())
        try {
            val sdp = runBlocking { session.createOffer() }
            assertTrue(sdp.startsWith("v=0"))
            assertTrue(sdp.contains("m=audio"))
            assertTrue(sdp.contains("a=sendrecv"))
            assertTrue(sdp.contains("m=application"))
            // Candidate presence depends on the test network; ICE and DTLS setup do not.
            assertTrue(sdp.contains("a=ice-ufrag:"))
            assertTrue(sdp.contains("a=ice-pwd:"))
            assertTrue(sdp.contains("a=fingerprint:"))
            assertTrue(sdp.contains("a=setup:actpass"))
            assertEquals(RealtimeMediaState.OfferReady(sdp), session.state.value)
        } finally {
            session.close()
            session.close()
        }
        assertEquals(RealtimeMediaState.Closed, session.state.value)
        assertEquals(AudioFocusState.NONE, session.controls.value.focus)
    }

    @Test
    fun liveOfferSendsAudioAndMuteSurvivesClose() {
        val session = factory.create(RealtimeMediaConfig())
        try {
            val sdp = runBlocking { session.createOffer() }
            assertTrue(sdp.contains("m=audio"))
            assertTrue(sdp.contains("a=sendrecv"))
            assertEquals(AudioFocusState.HELD, session.controls.value.focus)
            session.setMicrophoneMuted(true)
            assertTrue(session.controls.value.microphoneMuted)
        } finally {
            session.close()
        }
        assertTrue(session.controls.value.microphoneMuted)
        assertEquals(RealtimeMediaState.Closed, session.state.value)
    }

    @Test
    fun garbageAnswerFailsWithoutCrashing() {
        val session = factory.create(RealtimeMediaConfig())
        runBlocking { session.createOffer() }
        val error =
            runCatching { runBlocking { session.acceptAnswer("not an sdp") } }.exceptionOrNull()
        assertTrue(error is MediaException)
        assertTrue(session.state.value is RealtimeMediaState.Failed)
        session.close()
        assertTrue(session.state.value is RealtimeMediaState.Failed)
    }
}
