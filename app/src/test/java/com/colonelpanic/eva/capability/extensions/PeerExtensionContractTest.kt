package com.colonelpanic.eva.capability.extensions

import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Describe replies captured from the Mova and Paseo providers, checked against EVA's codec and
 * the receipt-state convention in docs/extension-protocol.md section 9.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PeerExtensionContractTest {
    private fun descriptor(name: String) =
        ExtensionProtocol
            .describe(requireNotNull(javaClass.getResourceAsStream("/extensions/$name-describe.json")).reader().readText())
            .descriptor!!

    private fun reply(
        status: String,
        reason: String?,
        state: String,
        invocation: String,
    ) = """{"protocolVersion":1,"status":"$status","reasonCode":${reason?.let { "\"$it\"" } ?: "null"},"truncated":false,""" +
        """"content":[{"type":"text","text":"$state"}],"structuredContent":{"state":"$state","invocationId":"$invocation"}}"""

    @Test
    fun `Mova describes background writes EVA can grant and run without a foreground`() {
        val mova = descriptor("mova")
        val tools = mova.capabilities.associateBy { it.name }
        assertEquals(
            setOf("create_todo", "complete_todo", "update_todo", "delete_todo"),
            tools.values
                .filter { it.effect == Effect.WRITE }
                .map { it.name }
                .toSet(),
        )
        assertEquals(Effect.READ, tools.getValue("invocation_status").effect)
        assertTrue(tools.values.filter { it.effect == Effect.WRITE }.all { it.maxWaitMillis == 20_000L })
        assertTrue(mova.revision.endsWith(mova.authorizationScopeRevision))
    }

    @Test
    fun `receipt states map onto the envelope statuses EVA reports`() =
        runTest {
            val mova = descriptor("mova")
            val complete = mova.capabilities.single { it.name == "complete_todo" }
            val fake = FakeExtensionConnector()
            val backend =
                ExtensionBackend(
                    extensionIdentity,
                    mova,
                    complete,
                    ExtensionConnectionManager(fake) { testScheduler.currentTime },
                    StandardTestDispatcher(testScheduler),
                )
            val proposal = ToolProposal("provider:s:call", "extension.example.app.complete_todo", mapOf("id" to "abc"), "done", "r")
            val invocation = ExtensionBackend.invocationId(proposal.callId)
            val expected =
                listOf(
                    Triple("completed", null, "completed") to InvocationStatus.COMPLETED,
                    Triple("handed_off", null, "accepted") to InvocationStatus.HANDED_OFF,
                    Triple("unknown", null, "uncertain") to InvocationStatus.UNKNOWN,
                    Triple("failed", null, "failed") to InvocationStatus.FAILED,
                    Triple("not_executed", null, "rejected") to InvocationStatus.NOT_EXECUTED,
                    Triple("not_executed", "deadline_exceeded", "not_sent") to InvocationStatus.NOT_EXECUTED,
                    Triple("not_executed", "invalid_arguments", "request_id_conflict") to InvocationStatus.NOT_EXECUTED,
                    Triple("not_executed", "not_configured", "needs_unlock") to InvocationStatus.NOT_EXECUTED,
                )
            for ((wire, status) in expected) {
                fake.reply = reply(wire.first, wire.second, wire.third, invocation)
                val outcome = backend.execute(proposal)
                assertEquals(wire.third, status, outcome.status)
                assertEquals(JsonPrimitive(wire.third), outcome.data!!["state"])
                wire.second?.let { assertTrue(outcome.message.contains(it)) }
            }
            assertEquals(invocation, fake.id)
            assertEquals(mova.revision, fake.revision)
            assertEquals(Json.parseToJsonElement("""{"id":"abc"}"""), Json.parseToJsonElement(fake.arguments!!).jsonObject)
        }
}
