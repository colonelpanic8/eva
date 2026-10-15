package com.colonelpanic.eva.adapters.android

import android.app.Application
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.telecom.Call
import androidx.test.core.app.ApplicationProvider
import com.colonelpanic.eva.call.AnsweredEvidence
import com.colonelpanic.eva.call.CallMedia
import com.colonelpanic.eva.call.CallMediaKind
import com.colonelpanic.eva.call.CallNotPlaced
import com.colonelpanic.eva.call.CallProviderTraits
import com.colonelpanic.eva.call.EndCause
import com.colonelpanic.eva.call.LegObservation
import com.colonelpanic.eva.call.LegState
import com.colonelpanic.eva.call.PcmFrame
import com.colonelpanic.eva.call.PlaceCallRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class CallEngineTest {
    private class Helper : ICallAudio.Default() {
        @Volatile var mode = AudioManager.MODE_NORMAL

        @Volatile var broken = false
        var endCalls = 0
        val muted = mutableListOf<Boolean>()

        override fun interceptable() = true

        override fun callState(): String {
            check(!broken) { "helper died" }
            return """{"mode":$mode,"calls":[]}"""
        }

        override fun endCall() {
            endCalls++
        }

        override fun setMicrophoneMuted(muted: Boolean) {
            this.muted += muted
        }

        /** EVA's end of a socket whose other end nothing reads or writes. */
        override fun openAudio(sampleRateHz: Int): ParcelFileDescriptor =
            ParcelFileDescriptor.createSocketPair().also { sockets += it }.first()

        val sockets = mutableListOf<Array<ParcelFileDescriptor>>()
    }

    private class Access(
        val helper: Helper,
        var unavailable: String? = null,
    ) : CallAudioAccess {
        override suspend fun unavailableReason() = unavailable

        override suspend fun helper() = helper
    }

    private class Transport(
        keypad: Boolean = true,
        override val redirect: CallRedirect = CallRedirect.PSTN,
    ) : HelperAudioTransport {
        override val id = "fake"
        override val traits = CallProviderTraits(CallMediaKind.LOCAL_PCM, keypad, true, true, true)
        override val needsCallControl = false
        override val grantNote = "granted"
        override val lostCheck = "Check the fake line."
        var unavailable: String? = null
        var refusal: String? = null
        var doubt: String? = null
        val dialed = mutableListOf<String>()

        override suspend fun unavailableReason() = unavailable

        override suspend fun dial(
            number: String,
            helper: ICallAudio,
        ) {
            refusal?.let { throw CallNotPlaced(it) }
            dialed += number
        }

        override fun owns(
            call: Call,
            number: String?,
        ) = true

        override fun observer() =
            CallObserver {
                if (it.audioMode ==
                    AudioManager.MODE_IN_CALL
                ) {
                    LegObservation.Live(AnsweredEvidence.PROVIDER_ASSERTION)
                } else {
                    LegObservation.Pending
                }
            }

        override fun recoveryDoubt(signals: CallSignals) = doubt
    }

    private val helper = Helper()
    private val access = Access(helper)
    private val transport = Transport()
    private val engine =
        CallEngine(ApplicationProvider.getApplicationContext(), access, transport, isEmergency = { it == "911" })

    private fun refusal(number: String = "+15592493930") =
        runBlocking { runCatching { engine.place(PlaceCallRequest("leg", number)) }.exceptionOrNull() }

    @Test fun refusesBeforeDialingWithTheReasonForTheOwner() {
        assertEquals("EVA does not place emergency calls on the owner's behalf.", refusal("911")!!.message)
        helper.mode = AudioManager.MODE_IN_CALL
        assertEquals("Another call is already in progress. End it before EVA places a new one.", refusal()!!.message)
        helper.mode = AudioManager.MODE_NORMAL
        transport.refusal = "No saved contact has that number."
        val refused = refusal()
        assertTrue(refused is CallNotPlaced)
        assertEquals("No saved contact has that number.", refused!!.message)
        assertTrue(transport.dialed.isEmpty())
    }

    @Test fun availabilityNamesTheTransportThenShizuku() =
        runBlocking<Unit> {
            transport.unavailable = "Grant the Phone permission."
            access.unavailable = "Shizuku is not running."
            assertEquals("Grant the Phone permission.", engine.unavailableReason())
            transport.unavailable = null
            assertEquals("Shizuku is not running. Cellular call audio runs through Shizuku.", engine.unavailableReason())
            access.unavailable = null
            assertNull(engine.unavailableReason())
        }

    @Test fun aPlacedLegHangsUpThroughTheHelperWithoutTelecomAndEndsAsLostWithCleanup() =
        runBlocking<Unit> {
            val leg = engine.place(PlaceCallRequest("leg", "+15592493930"))
            assertEquals(listOf("+15592493930"), transport.dialed)
            assertEquals(LegState.Placing, leg.state.value)
            leg.hangUp()
            assertEquals(1, helper.endCalls)
            helper.broken = true
            val ended = withTimeout(5_000) { leg.state.first { it is LegState.Ended } } as LegState.Ended
            assertEquals(EndCause.LOCAL_HANGUP, ended.cause)
            assertEquals(false, helper.muted.last())
        }

    @Test fun aKeypadlessTransportRefusesKeysInsteadOfPlayingTones() =
        runBlocking<Unit> {
            val keypadless =
                CallEngine(ApplicationProvider.getApplicationContext(), access, Transport(keypad = false), isEmergency = { false })
            val leg = keypadless.place(PlaceCallRequest("leg", "+15592493930"))
            assertEquals("fake calls have no keypad.", runCatching { leg.sendDtmf("1") }.exceptionOrNull()!!.message)
            helper.broken = true
            withTimeout(5_000) { leg.state.first { it is LegState.Ended } }
        }

    @Test fun reattachFindsOnlyALiveCallAndRefusesToGuess() =
        runBlocking<Unit> {
            assertNull(engine.reattach("leg"))
            transport.doubt = "Cannot tell whose VoIP call this is."
            assertEquals("Cannot tell whose VoIP call this is.", runCatching { engine.reattach("leg") }.exceptionOrNull()!!.message)
            transport.doubt = null
            helper.mode = AudioManager.MODE_IN_CALL
            val leg = engine.reattach("leg")!!
            assertEquals("leg", leg.id)
            assertEquals(LegState.Ringing, leg.state.value)
            helper.broken = true
            val ended = withTimeout(5_000) { leg.state.first { it is LegState.Ended } } as LegState.Ended
            assertEquals(EndCause.FAILED, ended.cause)
            assertTrue(ended.detail!!.endsWith("Check the fake line."))
        }

    /** An SDK that renders a constant other party once rendering starts, and records every uplink sample. */
    private class Port : SdkAudioPort {
        @Volatile var rendering = false
        val pushed: MutableList<Short> = Collections.synchronizedList(mutableListOf())

        override fun pull(chunk: ByteBuffer): Boolean {
            if (!rendering) return false
            chunk.order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until chunk.capacity() / 2) chunk.putShort(2 * i, REMOTE)
            return true
        }

        override fun push(chunk: ByteBuffer): Boolean {
            chunk.order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until chunk.capacity() / 2) pushed += chunk.getShort(2 * i)
            return true
        }

        fun sent(sample: Int) = synchronized(pushed) { sample.toShort() in pushed }
    }

    private class Microphone : OwnerMicrophone {
        @Volatile var released = false

        override fun read(samples: ShortArray) = samples.fill(OWNER)

        override fun release() {
            released = true
        }
    }

    private class SdkCall(
        override val audio: SdkAudioPort,
    ) : InAppCall {
        @Volatile var observation: LegObservation = LegObservation.Pending
        var hungUp = false
        override val observer = "the fake SDK"
        override val lostCheck = "Check the fake SDK."

        override suspend fun observe() = observation

        override suspend fun sendDtmf(
            digits: String,
            inBand: suspend (String) -> Unit,
        ) = "Sent as SDK signalling."

        override suspend fun hangUp() {
            hungUp = true
            observation = LegObservation.Gone(null, "The SDK ended the call.")
        }
    }

    private class InApp(
        val port: Port,
    ) : InAppAudioTransport {
        override val id = "sdk"
        override val traits = CallProviderTraits(CallMediaKind.LOCAL_PCM, true, false, false, true)
        override val lostCheck = "Check the fake SDK."
        var unavailable: String? = null
        var call: SdkCall? = null

        override suspend fun unavailableReason() = unavailable

        override fun busy() = call?.observation?.let { it !is LegObservation.Gone } ?: false

        override suspend fun dial(number: String) = SdkCall(port).also { call = it }
    }

    @Test fun anInAppCallCarriesItsSdksAudioAndMixesTheOwnersMicrophoneIntoTheUplinkOnTheirFloor() =
        runBlocking<Unit> {
            val port = Port()
            val sdk = InApp(port)
            val microphone = Microphone()
            val engine =
                CallEngine(ApplicationProvider.getApplicationContext(), null, sdk, isEmergency = { false }, microphone = { microphone })
            assertNull(engine.unavailableReason())
            val leg = engine.place(PlaceCallRequest("leg", "+15592493930"))
            val call = sdk.call!!
            assertEquals(LegState.Placing, leg.state.value)
            assertEquals(
                "Another call is already in progress. End it before EVA places a new one.",
                runCatching { engine.place(PlaceCallRequest("second", "+15592493930")) }.exceptionOrNull()!!.message,
            )

            call.observation = LegObservation.Live(AnsweredEvidence.PROVIDER_ASSERTION)
            port.rendering = true
            withTimeout(5_000) { leg.state.first { it is LegState.Connected } }
            val media = leg.media as CallMedia.LocalPcm
            val heard = withTimeout(5_000) { media.remoteFrames.first() }
            assertTrue(heard.samples.all { it == REMOTE })

            media.sendAgentFrame(PcmFrame(ShortArray(480) { AGENT }, 24_000))
            withTimeout(5_000) { while (!port.sent(AGENT.toInt())) delay(20) }
            assertTrue("The owner's microphone reached the other party before they took the floor.", !port.sent(OWNER.toInt()))
            leg.setOwnerMicrophone(true)
            media.sendAgentFrame(PcmFrame(ShortArray(480) { AGENT }, 24_000))
            withTimeout(5_000) { while (!port.sent(AGENT + OWNER)) delay(20) }
            leg.setOwnerMicrophone(false)
            assertTrue(microphone.released)

            assertEquals("Sent as SDK signalling.", leg.sendDtmf("1"))
            leg.hangUp()
            assertTrue(call.hungUp)
            val ended = withTimeout(5_000) { leg.state.first { it is LegState.Ended } } as LegState.Ended
            assertEquals(EndCause.LOCAL_HANGUP, ended.cause)
        }

    @Test fun anInAppTransportThatIsNotReadyDialsNothingAndNeedsNoShizuku() =
        runBlocking<Unit> {
            val sdk = InApp(Port())
            val engine = CallEngine(ApplicationProvider.getApplicationContext(), null, sdk, isEmergency = { false })
            sdk.unavailable = "Twilio calling is not set up."
            assertEquals("Twilio calling is not set up.", engine.unavailableReason())
            val refused = runCatching { engine.place(PlaceCallRequest("leg", "+15592493930")) }.exceptionOrNull()
            assertTrue(refused is CallNotPlaced)
            assertEquals("Twilio calling is not set up.", refused!!.message)
            assertNull(sdk.call)
            assertNull(engine.reattach("leg"))
        }

    private companion object {
        const val REMOTE: Short = 1_000
        const val AGENT: Short = 2_000
        const val OWNER: Short = 500
    }
}
