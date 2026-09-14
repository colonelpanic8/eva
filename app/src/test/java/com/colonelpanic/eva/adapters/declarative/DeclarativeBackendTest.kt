package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InteractionMode
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.capability.WaitBudget
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeclarativeBackendTest {
    private class FakeHost : DeclarativeHost {
        var calls = 0
        var timeout = 0L
        var fail = false

        override suspend fun unavailableReason(binding: DeclarativeBinding): String? = null

        override suspend fun launch(request: IntentRequest): ExecutionOutcome {
            calls++
            return ExecutionOutcome(InvocationStatus.HANDED_OFF, "Opened the target app")
        }

        override suspend fun query(
            request: ContentRequest,
            timeoutMillis: Long,
        ): ContentRows {
            calls++
            timeout = timeoutMillis
            return ContentRows(listOf(JsonObject(mapOf("title" to JsonPrimitive("Found")))), false)
        }

        override suspend fun request(
            request: HttpRequest,
            timeoutMillis: Long,
        ): HttpResponse {
            calls++
            timeout = timeoutMillis
            check(!fail)
            return HttpResponse(200, """{"status":"created"}""")
        }
    }

    @Test
    fun `fake hosts receive typed requests budget and failures never retry`() =
        runTest {
            val host = FakeHost()
            val budget = WaitBudget(InteractionMode.VOICE, 20_000, 30_000, 90_000)
            val capability = PackageCodec.decode(packageJson(httpBinding, "synchronous", false)).capabilities.single()
            val backend = DeclarativeBackend(capability, host) { budget }
            val proposal = ToolProposal("call", "extension.test.capture", mapOf("title" to "Test"), "capture", "revision")
            assertEquals(InvocationStatus.NOT_EXECUTED, backend.execute(proposal.arguments).status)
            assertEquals(InvocationStatus.NOT_EXECUTED, backend.execute(proposal.copy(arguments = emptyMap())).status)
            assertEquals(0, host.calls)
            val completed = backend.execute(proposal)
            assertEquals(InvocationStatus.COMPLETED, completed.status)
            assertEquals(60_000, host.timeout)
            assertTrue(completed.message.contains(budget.receipt()))
            host.fail = true
            assertEquals(InvocationStatus.UNKNOWN, backend.execute(proposal.copy(callId = "second")).status)
            assertEquals(2, host.calls)
        }

    @Test
    fun `intent remains a handoff while bounded content produces read evidence`() =
        runTest {
            val host = FakeHost()
            val budget = WaitBudget(InteractionMode.TYPED, 30_000, null, null)
            val proposal = ToolProposal("call", "extension.test.capture", mapOf("title" to "Test"), "capture", "revision")
            val intent = PackageCodec.decode(packageJson(intentBinding)).capabilities.single()
            assertEquals(InvocationStatus.HANDED_OFF, DeclarativeBackend(intent, host) { budget }.execute(proposal).status)
            val content = PackageCodec.decode(packageJson(contentBinding, "synchronous", false)).capabilities.single()
            val result = DeclarativeBackend(content, host) { budget }.execute(proposal)
            assertEquals(InvocationStatus.COMPLETED, result.status)
            assertTrue(result.message.contains("Found"))
            assertEquals(30_000, host.timeout)
            assertEquals(2, host.calls)
        }
}
