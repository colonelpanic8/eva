package com.colonelpanic.eva.adapters.android

import android.Manifest
import android.app.Application
import android.telecom.TelecomManager
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PhoneCallBackendTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val telecom get() = shadowOf(app.getSystemService(TelecomManager::class.java))
    private var dialed = 0
    private val dialer =
        object : ExecutionBackend {
            override suspend fun unavailableReason(): String? = "Open EVA before sending this request."

            override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                dialed++
                return ExecutionOutcome(InvocationStatus.HANDED_OFF, "Dialer opened with the number.")
            }
        }

    private fun backend(emergency: Boolean = false) = PhoneCallBackend(app, dialer) { emergency }

    @Test
    fun `with the phone permission the call is placed without a screen`() =
        runBlocking {
            shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
            val backend = backend()
            assertNull(backend.unavailableReason())
            val outcome = backend.execute(mapOf("number" to "+1 415-555-0100"))
            assertEquals(InvocationStatus.HANDED_OFF, outcome.status)
            assertEquals("tel:%2B1%20415-555-0100", telecom.onlyOutgoingCall.address.toString())
            assertEquals(0, dialed)
        }

    @Test
    fun `without the permission the dialer opens and says how to call directly`() =
        runBlocking {
            shadowOf(app).denyPermissions(Manifest.permission.CALL_PHONE)
            val backend = backend()
            assertTrue(backend.unavailableReason()!!.contains("Phone permission"))
            val outcome = backend.execute(mapOf("number" to "4155550100"))
            assertEquals(InvocationStatus.HANDED_OFF, outcome.status)
            assertTrue(outcome.message.contains("press call"))
            assertEquals(1, dialed)
            assertTrue(telecom.allOutgoingCalls.isEmpty())
        }

    @Test
    fun `an emergency number always goes to the dialer`() =
        runBlocking {
            shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
            val outcome = backend(emergency = true).execute(mapOf("number" to "911"))
            assertEquals("Dialer opened with the number.", outcome.message)
            assertEquals(1, dialed)
            assertTrue(telecom.allOutgoingCalls.isEmpty())
        }
}
