package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.call.AnsweredEvidence
import com.colonelpanic.eva.call.CallNotPlaced
import com.colonelpanic.eva.call.EndCause
import com.colonelpanic.eva.call.LegObservation
import com.colonelpanic.eva.call.TwilioApiKey
import com.colonelpanic.eva.data.configuration.TwilioCallsConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64

class TwilioCallProviderTest {
    private val configuration = TwilioCallsConfiguration("AC" + "a".repeat(32), "AP" + "c".repeat(32), "+15592493930")

    private class Account(
        var configuration: TwilioCallsConfiguration?,
        var key: TwilioApiKey?,
    ) : TwilioCallAccount {
        override fun configuration() = configuration

        override fun apiKey() = key
    }

    private class Sdk(
        val answer: TwilioCallEvents.() -> Unit,
    ) : TwilioVoiceSdk,
        TwilioCallHandle {
        override val audio =
            object : SdkAudioPort {
                override fun pull(chunk: ByteBuffer) = false

                override fun push(chunk: ByteBuffer) = false
            }
        var token: String? = null
        var params: Map<String, String>? = null
        val digits = mutableListOf<String>()
        var disconnects = 0
        lateinit var events: TwilioCallEvents

        override fun connect(
            accessToken: String,
            params: Map<String, String>,
            events: TwilioCallEvents,
        ): TwilioCallHandle {
            token = accessToken
            this.params = params
            this.events = events
            events.answer()
            return this
        }

        override fun sendDigits(digits: String) {
            this.digits += digits
        }

        override fun disconnect() {
            disconnects++
        }
    }

    private val account = Account(configuration, TwilioApiKey("SK" + "b".repeat(32), "test-secret"))

    private fun transport(
        sdk: Sdk,
        microphone: Boolean = true,
    ) = TwilioTransport(account, sdk, { "+" + it.filter(Char::isDigit) }, nowSeconds = { 1_760_000_000 }, main = Dispatchers.Unconfined) {
        microphone
    }

    @Test fun readinessNamesWhatIsMissingAndWhereTheOwnerSetsItUp() =
        runBlocking<Unit> {
            val sdk = Sdk { ringing() }
            assertNull(transport(sdk).unavailableReason())
            assertTrue(transport(sdk, microphone = false).unavailableReason()!!.startsWith("Grant EVA the Microphone permission"))
            account.key = null
            assertTrue(transport(sdk).unavailableReason()!!.startsWith("This phone has no Twilio API key for calls"))
            account.configuration = null
            assertTrue(transport(sdk).unavailableReason()!!.startsWith("Twilio calling is not set up"))
            assertTrue(runCatching { transport(sdk).dial("+15551234567") }.exceptionOrNull() is CallNotPlaced)
            account.configuration = TwilioCallsConfiguration("AC" + "a".repeat(32), "AP" + "c".repeat(32), "+15592493930")
            account.key = TwilioApiKey("SK" + "b".repeat(32), "test-secret")
            assertTrue(
                runCatching { transport(sdk).dial("611") }.exceptionOrNull()!!.message!!.startsWith("The Twilio route calls only full"),
            )
            assertNull(sdk.token)
        }

    @Test fun dialsTheDestinationThroughTheConfiguredTwimlAppWithATokenMintedOnThePhone() =
        runBlocking<Unit> {
            val sdk = Sdk { ringing() }
            val transport = transport(sdk)
            val call = transport.dial("+1 (555) 123-4567")
            assertEquals(mapOf("To" to "+15551234567"), sdk.params)
            val claims = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(sdk.token!!.split('.')[1]))).jsonObject
            assertEquals(configuration.accountSid, claims["sub"]!!.jsonPrimitive.content)
            assertEquals(
                configuration.twimlAppSid,
                claims["grants"]!!
                    .jsonObject["voice"]!!
                    .jsonObject["outgoing"]!!
                    .jsonObject["application_sid"]!!
                    .jsonPrimitive.content,
            )
            assertTrue(transport.busy())
            assertEquals("+15592493930", transport.traits.callerId)
            assertEquals(LegObservation.Live(null), call.observe())
        }

    @Test fun aTokenOrApplicationRefusalBeforeRingingDialedNothing() =
        runBlocking<Unit> {
            val sdk = Sdk { connectFailed(TwilioError(20_101, "Invalid Access Token", null)) }
            val transport = transport(sdk)
            val refused = runCatching { transport.dial("+15551234567") }.exceptionOrNull()
            assertTrue(refused is CallNotPlaced)
            assertEquals("Twilio refused the call before dialing anyone: Twilio error 20101: Invalid Access Token.", refused!!.message)
            assertFalse(transport.busy())
        }

    @Test fun followsTheSdksEventsIntoLegObservationsWithTheirCauses() =
        runBlocking<Unit> {
            val sdk = Sdk { ringing() }
            val call = transport(sdk).dial("+15551234567") as TwilioCall
            sdk.events.connected()
            assertEquals(LegObservation.Live(AnsweredEvidence.PROVIDER_ASSERTION), call.observe())
            sdk.events.reconnecting(TwilioError(53_001, "Signaling connection disconnected", null))
            assertEquals(LegObservation.Live(AnsweredEvidence.PROVIDER_ASSERTION), call.observe())
            sdk.events.disconnected(TwilioError(31_005, "Connection error", "A connection error occurred"))
            assertEquals(
                LegObservation.Gone(EndCause.DROPPED, "Twilio error 31005: Connection error (A connection error occurred)"),
                call.observe(),
            )
            sdk.events.disconnected(null)
            assertEquals("The first end is final.", EndCause.DROPPED, (call.observe() as LegObservation.Gone).cause)

            assertEquals(EndCause.BUSY, TwilioCall.endCause(31_486, answered = false))
            assertNull("A 603 can be no answer or a failed dial, so it names no cause.", TwilioCall.endCause(31_603, answered = false))
            assertEquals(EndCause.FAILED, TwilioCall.endCause(31_005, answered = false))
            assertEquals(EndCause.FAILED, TwilioCall.endCause(13_224, answered = false))
            assertEquals(EndCause.NO_ANSWER, TwilioCall.endCause(31_480, answered = false))
            assertNull(TwilioCall.endCause(31_000, answered = true))
        }

    @Test fun keysAreSignalledThroughTheSdkOnlyOnceTheCallIsAnsweredAndHangUpDisconnects() =
        runBlocking<Unit> {
            val sdk = Sdk { ringing() }
            val call = transport(sdk).dial("+15551234567")
            assertEquals(
                "The Twilio call is not connected.",
                runCatching { call.sendDtmf("1") { error("no tones") } }.exceptionOrNull()!!.message,
            )
            sdk.events.connected()
            assertEquals("Sent as keypad signalling (RFC 2833) through the Twilio Voice SDK.", call.sendDtmf("4#") { error("no tones") })
            assertEquals(listOf("4", "#"), sdk.digits)
            call.hangUp()
            assertEquals(1, sdk.disconnects)
        }
}
