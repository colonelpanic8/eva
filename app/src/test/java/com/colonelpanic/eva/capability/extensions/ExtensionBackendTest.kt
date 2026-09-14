package com.colonelpanic.eva.capability.extensions

import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExtensionBackendTest {
    private val descriptor = ExtensionProtocol.describe(extensionDescription).descriptor!!
    private val proposal = ToolProposal("call-1", "extension.example.app.read", emptyMap(), "read", "registry")

    @Test
    fun `backend propagates expected revision identity deadline and every terminal status`() =
        runTest {
            val fake = FakeExtensionConnector()
            val backend =
                ExtensionBackend(
                    extensionIdentity,
                    descriptor,
                    extensionCapability,
                    ExtensionConnectionManager(fake) { testScheduler.currentTime },
                    StandardTestDispatcher(testScheduler),
                )
            for (status in listOf(
                InvocationStatus.COMPLETED,
                InvocationStatus.NOT_EXECUTED,
                InvocationStatus.FAILED,
                InvocationStatus.HANDED_OFF,
                InvocationStatus.UNKNOWN,
            )) {
                fake.reply = ExtensionProtocol.encodeResult(ResultReply(ExecutionOutcome(status, "evidence"), null, false))
                assertEquals(ExecutionOutcome(status, "evidence"), backend.execute(proposal))
            }
            assertEquals("v1", fake.revision)
            assertEquals("call-1", fake.id)
            assertEquals(1000, fake.deadline)
            assertEquals(5, fake.submits)
            assertEquals(extensionIdentity.component, backend.definition.source!!.id)
        }

    @Test
    fun `reason and truncation survive receipt while malformed response is unknown`() =
        runTest {
            val fake = FakeExtensionConnector()
            val backend =
                ExtensionBackend(
                    extensionIdentity,
                    descriptor,
                    extensionCapability,
                    ExtensionConnectionManager(fake) { testScheduler.currentTime },
                    StandardTestDispatcher(testScheduler),
                )
            for (reason in listOf(
                "stale_descriptor",
                "not_configured",
                "busy",
                "invalid_arguments",
                "unauthorized_caller",
                "deadline_exceeded",
            )) {
                fake.reply =
                    ExtensionProtocol.encodeResult(ResultReply(ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "details"), reason, true))
                val outcome = backend.execute(proposal)
                assertEquals(InvocationStatus.NOT_EXECUTED, outcome.status)
                assertTrue(outcome.message.contains(reason))
                assertTrue(outcome.message.contains("incomplete"))
            }
            fake.reply = "{}"
            assertEquals(InvocationStatus.UNKNOWN, backend.execute(proposal).status)
            fake.reply = null
            assertEquals(InvocationStatus.UNKNOWN, backend.execute(proposal).status)
            assertEquals(8, fake.submits)
        }

    @Test
    fun `withheld grants invalid arguments and unavailable binding never submit`() =
        runTest {
            val fake = FakeExtensionConnector()
            var denied: String? = "Grant required"
            val backend =
                GrantedExecutionBackend(
                    ExtensionBackend(
                        extensionIdentity,
                        descriptor,
                        extensionCapability,
                        ExtensionConnectionManager(fake) { testScheduler.currentTime },
                        StandardTestDispatcher(testScheduler),
                    ),
                ) { denied }
            assertEquals(InvocationStatus.NOT_EXECUTED, backend.execute(proposal).status)
            assertEquals(0, fake.binds)
            denied = null
            assertEquals(InvocationStatus.NOT_EXECUTED, backend.execute(proposal.copy(arguments = mapOf("extra" to "bad"))).status)
            fake.bindFailure = true
            assertEquals(InvocationStatus.NOT_EXECUTED, backend.execute(proposal).status)
            assertEquals(0, fake.submits)
        }
}
