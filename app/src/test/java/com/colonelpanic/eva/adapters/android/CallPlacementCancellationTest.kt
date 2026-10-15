package com.colonelpanic.eva.adapters.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.colonelpanic.eva.call.CallNotPlaced
import com.colonelpanic.eva.call.PlaceCallRequest
import com.colonelpanic.eva.call.TwilioApiKey
import com.colonelpanic.eva.data.configuration.TwilioCallsConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class CallPlacementCancellationTest {
    @Test fun cancellationDuringSdkConnectDisconnectsTheHandleAndKeepsAdmissionUntilObservedEnd() =
        runBlocking<Unit> {
            val sdk = CancellingSdk()
            val account =
                object : TwilioCallAccount {
                    override fun configuration() = TwilioCallsConfiguration("AC" + "a".repeat(32), "AP" + "c".repeat(32), "+15592493930")

                    override fun apiKey() = TwilioApiKey("SK" + "b".repeat(32), "test-secret")
                }
            val transport =
                TwilioTransport(account, sdk, { it }, nowSeconds = { 1_760_000_000 }, main = Dispatchers.Unconfined) { true }
            val admission = CallAdmission()
            val engine =
                CallEngine(ApplicationProvider.getApplicationContext(), null, transport, isEmergency = { false }, admission = admission)

            val placing = async(start = CoroutineStart.LAZY) { engine.place(PlaceCallRequest("leg", "+15551234567")) }
            sdk.dialing = placing
            placing.start()
            assertTrue(runCatching { placing.await() }.exceptionOrNull() is CancellationException)
            assertEquals(1, sdk.disconnects)
            assertTrue(runCatching { admission.acquire() }.exceptionOrNull() is CallNotPlaced)

            sdk.events.disconnected(null)
            admission.acquire().release()
        }

    private class CancellingSdk :
        TwilioVoiceSdk,
        TwilioCallHandle {
        override val audio =
            object : SdkAudioPort {
                override fun pull(chunk: ByteBuffer) = false

                override fun push(chunk: ByteBuffer) = false
            }
        lateinit var events: TwilioCallEvents
        lateinit var dialing: Job
        var disconnects = 0

        override fun connect(
            accessToken: String,
            params: Map<String, String>,
            events: TwilioCallEvents,
        ): TwilioCallHandle {
            this.events = events
            events.ringing()
            dialing.cancel()
            return this
        }

        override fun sendDigits(digits: String) = Unit

        override fun disconnect() {
            disconnects++
        }
    }
}
