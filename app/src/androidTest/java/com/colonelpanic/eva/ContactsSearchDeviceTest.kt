package com.colonelpanic.eva

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactsSearchDeviceTest {
    private lateinit var eva: EvaApplication

    @Before
    fun launchEva() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        eva = context.applicationContext as EvaApplication
    }

    @Test
    fun contactsSearchAcceptsEachField() =
        runBlocking {
            for (field in listOf("name", "given", "family")) {
                val started = SystemClock.elapsedRealtime()
                val outcome =
                    eva.registry
                        .resolve(proposal(CapabilityRegistry.CONTACTS_SEARCH))!!
                        .execute(mapOf("query" to "Malison", "field" to field))
                Log.i(TAG, "contacts-$field ${SystemClock.elapsedRealtime() - started}ms ${outcome.status} ${outcome.message}")
                assertEquals(InvocationStatus.COMPLETED, outcome.status)
            }
        }

    private fun proposal(id: String) =
        com.colonelpanic.eva.capability.ToolProposal(
            callId = "device-$id",
            capabilityId = id,
            arguments = emptyMap(),
            request = "device verification",
            catalogRevision = eva.registry.snapshot.revision,
        )

    private companion object {
        const val TAG = "EvaContactsDeviceTest"
    }
}
