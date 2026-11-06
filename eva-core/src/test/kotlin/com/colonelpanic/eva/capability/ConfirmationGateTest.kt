package com.colonelpanic.eva.capability

import com.colonelpanic.eva.providers.ProviderToolDefinition
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationGateTest {
    private val repository = MemoryInvocationRepository()
    private val received = mutableListOf<Map<String, String>>()
    private val backend =
        object : ExecutionBackend {
            override suspend fun unavailableReason(): String? = null

            override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                received += arguments
                return ExecutionOutcome(InvocationStatus.COMPLETED, "Started")
            }
        }
    private val start =
        CapabilityDefinition(
            START,
            "Start a thread",
            "Start a coding thread.",
            Json
                .parseToJsonElement(
                    """
                    {"type":"object","properties":{
                      "host":{"type":"string"},"model":{"type":"string"},"prompt":{"type":"string"},
                      "workspace":{"type":"string","enum":["local","worktree"]}},
                    "required":["prompt"],"additionalProperties":false}
                    """,
                ).jsonObject,
            confirmation =
                ToolConfirmation(
                    linkedMapOf(
                        "host" to ConfirmedParameter(ParameterResolve.CONFIRM, "Host", "list_hosts"),
                        "model" to ConfirmedParameter(ParameterResolve.CONFIRM, "Model"),
                        "workspace" to ConfirmedParameter(ParameterResolve.CONFIRM, "Workspace"),
                    ),
                ),
        )
    private var preferences = emptyMap<String, ToolPreferences>()
    private val gate = ConfirmationGate({ preferences })
    private val registry =
        CapabilityRegistry(mapOf(START to backend, TestCapabilities.SEARCH to backend), listOf(start, TestCapabilities.search))
    private val dispatcher = CapabilityDispatcher(registry, repository, confirmations = gate)
    private var calls = 0

    private fun proposal(
        arguments: Map<String, String>,
        capability: String = START,
    ) = ToolProposal("call-${++calls}", capability, arguments, "start", registry.snapshot.revision)

    @Test
    fun `a capability without a declaration runs unchanged and is offered unchanged`() =
        runTest {
            val record = dispatcher.execute(proposal(mapOf("destination" to "Park"), TestCapabilities.SEARCH))
            assertEquals(InvocationStatus.COMPLETED, record.status)
            assertEquals(listOf(mapOf("destination" to "Park")), received)
            val tool = ProviderToolDefinition(TestCapabilities.SEARCH, "Search", "Search maps.", TestCapabilities.search.inputSchema)
            assertEquals(tool, gate.offer(tool, TestCapabilities.search))
        }

    @Test
    fun `unstated parameters are read back, and the confirmed call runs once without control arguments`() =
        runTest {
            val arguments = mapOf("prompt" to "Fix the build", "host" to "laptop", "model" to "opus", "workspace" to "local")
            val held = dispatcher.execute(proposal(arguments + ("_stated" to """["prompt"]""")))
            assertEquals(InvocationStatus.NOT_EXECUTED, held.status)
            assertTrue(received.isEmpty())
            val data = checkNotNull(held.data)
            assertEquals("confirmation_required", data.getValue("reason").jsonPrimitive.content)
            assertEquals(
                listOf("host", "model", "workspace"),
                data.getValue("unresolved").jsonArray.map {
                    it.jsonObject
                        .getValue("name")
                        .jsonPrimitive.content
                },
            )
            val workspace = data.getValue("unresolved").jsonArray[2].jsonObject
            assertEquals(JsonArray(listOf("local", "worktree").map { kotlinx.serialization.json.JsonPrimitive(it) }), workspace["choices"])
            assertTrue(held.message.contains("Host = laptop"))
            val token = data.getValue("_confirm").jsonPrimitive.content
            assertTrue(held.message.contains(token))

            val confirmed = dispatcher.execute(proposal(arguments + ("_confirm" to token) + ("_stated" to """["prompt"]""")))
            assertEquals(InvocationStatus.COMPLETED, confirmed.status)
            assertEquals(listOf(arguments), received)
        }

    @Test
    fun `stated values and saved defaults settle the call without a readback`() =
        runTest {
            preferences = mapOf(START to ToolPreferences(defaults = mapOf("host" to "laptop", "workspace" to "worktree", "color" to "red")))
            val record =
                dispatcher.execute(proposal(mapOf("prompt" to "Fix it", "model" to "opus", "_stated" to """["prompt","model"]""")))
            assertEquals(InvocationStatus.COMPLETED, record.status)
            assertEquals(listOf(mapOf("prompt" to "Fix it", "model" to "opus", "host" to "laptop", "workspace" to "worktree")), received)
        }

    @Test
    fun `a token does not carry over to changed arguments`() =
        runTest {
            val arguments = mapOf("prompt" to "Fix it", "host" to "laptop", "model" to "opus", "workspace" to "local")
            val token =
                dispatcher
                    .execute(proposal(arguments))
                    .data!!
                    .getValue("_confirm")
                    .jsonPrimitive.content
            val changed = dispatcher.execute(proposal(arguments + ("model" to "sonnet") + ("_confirm" to token)))
            assertEquals(InvocationStatus.NOT_EXECUTED, changed.status)
            assertTrue(received.isEmpty())
        }

    @Test
    fun `the configured level overrides the declaration`() =
        runTest {
            val everything = mapOf("prompt" to "Fix it", "host" to "laptop", "model" to "opus", "workspace" to "local")
            val stated = everything + ("_stated" to """["prompt","host","model","workspace"]""")
            preferences = mapOf(START to ToolPreferences(ConfirmLevel.ALWAYS))
            assertEquals(InvocationStatus.NOT_EXECUTED, dispatcher.execute(proposal(stated)).status)
            preferences = mapOf(START to ToolPreferences(ConfirmLevel.NEVER))
            assertEquals(InvocationStatus.COMPLETED, dispatcher.execute(proposal(everything)).status)
            assertEquals(listOf(everything), received)
        }

    @Test
    fun `an opted-in tool is offered stated and token arguments the schema checker accepts`() {
        val tool = ProviderToolDefinition(START, start.title, start.description, start.inputSchema)
        val offered = gate.offer(tool, start)
        ToolSchema.check(offered.inputSchema)
        val properties = offered.inputSchema.getValue("properties") as JsonObject
        assertEquals(
            listOf("host", "model", "prompt", "workspace"),
            properties
                .getValue("_stated")
                .jsonObject
                .getValue("items")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .map { it.jsonPrimitive.content },
        )
        assertTrue("_confirm" in properties)
        preferences = mapOf(START to ToolPreferences(ConfirmLevel.NEVER, mapOf("host" to "laptop")))
        assertEquals(tool, gate.offer(tool, start))
        assertNull(offered.inputSchema["required"]?.jsonArray?.firstOrNull { it.jsonPrimitive.content.startsWith("_") })
    }

    private companion object {
        const val START = "test.threads.start"
    }
}
