package com.colonelpanic.eva.data

import android.app.Application
import com.colonelpanic.eva.capability.CapabilitySource
import com.colonelpanic.eva.capability.InvocationRecord
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ReceiptProvenance
import com.colonelpanic.eva.capability.displayMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class JournalProvenanceTest {
    @Test
    fun `source arguments and display survive database reopen and recovery`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val name = "provenance-${UUID.randomUUID()}.db"
            val source = ReceiptProvenance(CapabilitySource("user0:fixture/.Service:signer", "Original provider"), "approved-digest")
            val record =
                InvocationRecord(
                    "call",
                    "fp",
                    "request",
                    null,
                    InvocationStatus.CLAIMED,
                    "Pending",
                    1,
                    "extension.fixture.search",
                    "revision",
                    title = "Original action",
                    arguments = mapOf("query" to "café\nquoted \"text\""),
                    provenance = source,
                )
            try {
                SqliteInvocationRepository(context, name).use {
                    it.claim(record)
                    it.transition("call", InvocationStatus.CLAIMED, InvocationStatus.DISPATCHING, "Dispatched")
                }
                SqliteInvocationRepository(context, name).use {
                    it.recoverInterrupted()
                    val recovered = it.history().single()
                    assertEquals(InvocationStatus.UNKNOWN, recovered.status)
                    assertEquals(record.arguments, recovered.arguments)
                    assertEquals(source, recovered.provenance)
                    assertEquals("Original action", recovered.title)
                    assertEquals("fp", recovered.fingerprint)
                    assertTrue(recovered.displayMessage().contains("Original provider"))
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
}
