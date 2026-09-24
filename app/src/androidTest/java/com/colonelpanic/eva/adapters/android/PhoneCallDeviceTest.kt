package com.colonelpanic.eva.adapters.android

import android.app.KeyguardManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Places a real call from EVA's process, in whatever lock state the device is left in. */
@RunWith(AndroidJUnit4::class)
class PhoneCallDeviceTest {
    @Test
    fun placesTheCallWithoutAnActivity() =
        runBlocking {
            val number = InstrumentationRegistry.getArguments().getString("evaCallNumber")
            assumeTrue("Requires evaCallNumber", !number.isNullOrBlank())
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val noDialer =
                object : ExecutionBackend {
                    override suspend fun unavailableReason(): String? = null

                    override suspend fun execute(arguments: Map<String, String>) =
                        ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "dialer fallback")
                }
            val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            val outcome = PhoneCallBackend(context, noDialer).execute(mapOf("number" to requireNotNull(number)))
            Log.i("EvaPhoneCallDevice", "locked=$locked status=${outcome.status} message=${outcome.message}")
            assertEquals(InvocationStatus.HANDED_OFF, outcome.status)
        }
}
