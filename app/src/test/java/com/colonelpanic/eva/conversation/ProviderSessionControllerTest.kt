package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.capability.CapabilityDefinition
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.providers.CallIdentity
import com.colonelpanic.eva.providers.ConversationInput
import com.colonelpanic.eva.providers.ConversationProvider
import com.colonelpanic.eva.providers.ConversationSession
import com.colonelpanic.eva.providers.CorrelatedToolResult
import com.colonelpanic.eva.providers.ProviderEvent
import com.colonelpanic.eva.providers.ResponseRequest
import com.colonelpanic.eva.providers.SessionOpenRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderSessionControllerTest {
    private val repository = MemoryInvocationRepository()
    private val provider = FakeProvider()
    private var executions = 0
    private val definition =
        CapabilityDefinition(
            "test.custom",
            "Custom action",
            "Execute a custom test action",
            Json
                .parseToJsonElement(
                    """{"type":"object","properties":{"place":{"type":"string","minLength":1}},
            "required":["place"],"additionalProperties":false}""",
                ).jsonObject,
        )
    private val registry =
        CapabilityRegistry(
            mapOf(
                definition.id to
                    object : ExecutionBackend {
                        override suspend fun unavailableReason(): String? = null

                        override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                            executions++
                            return ExecutionOutcome(InvocationStatus.HANDED_OFF, "Opened ${arguments.getValue("place")}")
                        }
                    },
            ),
            listOf(definition),
        )

    private val secondDefinition =
        CapabilityDefinition(
            "test.hideable",
            "Hideable action",
            "Execute an action the user can switch off",
            Json
                .parseToJsonElement(
                    """{"type":"object","properties":{"place":{"type":"string","minLength":1}},
            "required":["place"],"additionalProperties":false}""",
                ).jsonObject,
        )
    private val twoCapabilityRegistry =
        CapabilityRegistry(
            listOf(definition, secondDefinition).associate { entry ->
                entry.id to
                    object : ExecutionBackend {
                        override suspend fun unavailableReason(): String? = null

                        override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                            executions++
                            return ExecutionOutcome(InvocationStatus.HANDED_OFF, "Opened ${arguments.getValue("place")}")
                        }
                    }
            },
            listOf(definition, secondDefinition),
        )

    @Test
    fun `a switched off capability is never offered to the model`() =
        runTest {
            val controller =
                ProviderSessionController(
                    twoCapabilityRegistry,
                    CapabilityDispatcher(twoCapabilityRegistry, repository),
                    repository,
                    this,
                    { provider },
                    hiddenCapabilities = { setOf(secondDefinition.id) },
                )
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()

            assertEquals(
                listOf(definition.id),
                provider.request.catalog.tools
                    .map { it.capabilityId },
            )
            controller.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun `switching a capability back on offers it again under a different catalog revision`() =
        runTest {
            var hidden = setOf(secondDefinition.id)
            val controller =
                ProviderSessionController(
                    twoCapabilityRegistry,
                    CapabilityDispatcher(twoCapabilityRegistry, repository),
                    repository,
                    this,
                    { provider },
                    hiddenCapabilities = { hidden },
                )
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            val withoutIt = provider.request.catalog
            controller.disconnect()
            advanceUntilIdle()

            hidden = emptySet()
            controller.connect("unused")
            advanceUntilIdle()
            val withIt = provider.request.catalog

            assertEquals(listOf(definition.id, secondDefinition.id), withIt.tools.map { it.capabilityId })
            assertNotEquals(withoutIt.revision, withIt.revision)
            controller.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun `natural language uses a catalog supplied action and returns actual evidence`() =
        runTest {
            val controller = ProviderSessionController(registry, CapabilityDispatcher(registry, repository), repository, this, { provider })
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            controller.submit("Please show me the park")
            advanceUntilIdle()
            provider.call("first")
            advanceUntilIdle()
            assertEquals(1, executions)
            assertEquals("HANDED_OFF", provider.results.single().status)
            assertEquals("Opened Park", provider.results.single().message)
            assertEquals("Custom action", repository.history().single().title)
            provider.channel.send(ProviderEvent.AssistantText(provider.input.id, "The park is open.", false))
            provider.channel.send(ProviderEvent.ResponseEnded(provider.input.id, "completed"))
            advanceUntilIdle()
            assertFalse(controller.state.value.isSubmitting)
            assertEquals(
                "The park is open.",
                controller.state.value.entries
                    .first { it.id == provider.input.id }
                    .response,
            )
            controller.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun `second call in an input is rejected by the phone independently of the relay`() =
        runTest {
            val controller = ProviderSessionController(registry, CapabilityDispatcher(registry, repository), repository, this, { provider })
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            controller.submit("Open two places")
            advanceUntilIdle()
            provider.call("first")
            advanceUntilIdle()
            provider.call("second")
            advanceUntilIdle()
            assertEquals(1, executions)
            assertEquals(listOf("HANDED_OFF", "NOT_EXECUTED"), provider.results.map { it.status })
            controller.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun `claim storage failure stops the session without executing an action`() =
        runTest {
            val controller = ProviderSessionController(registry, CapabilityDispatcher(registry, repository), repository, this, { provider })
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            controller.submit("Open the park")
            advanceUntilIdle()
            repository.failClaim = true
            provider.call("first")
            advanceUntilIdle()
            assertEquals(0, executions)
            assertNotNull(controller.state.value.errorMessage)
            assertEquals(ProviderStatus.DISCONNECTED, controller.state.value.providerStatus)
            assertFalse(controller.state.value.isSubmitting)
        }

    @Test
    fun `a session is bracketed and its actions hang off the turn that ran them`() =
        runTest {
            val controller = ProviderSessionController(registry, CapabilityDispatcher(registry, repository), repository, this, { provider })
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            val opened =
                controller.state.value.entries
                    .single { it.status == EntryStatus.SESSION }
            assertEquals("Text session", opened.response)
            controller.submit("Please show me the park")
            advanceUntilIdle()
            provider.call("first")
            advanceUntilIdle()
            val action =
                controller.state.value.entries
                    .single { it.id.endsWith(":first") }
            assertEquals(provider.input.id, action.parentId)
            assertEquals(listOf(opened.id, provider.input.id), groups(controller.state.value.entries).map { it.entry.id })
            controller.disconnect()
            advanceUntilIdle()
            assertEquals(
                listOf("Text session", "Session ended"),
                controller.state.value.entries
                    .filter { it.status == EntryStatus.SESSION }
                    .map { it.response },
            )
        }

    @Test
    fun `connection keeps its original revision and removed calls get durable rejections`() =
        runTest {
            val oldRevision = registry.snapshot.revision
            val controller = ProviderSessionController(registry, CapabilityDispatcher(registry, repository), repository, this, { provider })
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            val projection = provider.request.catalog.revision
            registry.replace(emptyMap(), emptyList())
            controller.submit("Open Park")
            advanceUntilIdle()
            provider.call("removed")
            advanceUntilIdle()
            assertEquals(projection, provider.request.catalog.revision)
            assertEquals(oldRevision, repository.history().single().catalogRevision)
            assertEquals("NOT_EXECUTED", provider.results.single().status)
            assertEquals(0, executions)
            controller.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun `new definitions are captured on next open rather than controller construction`() =
        runTest {
            var active = provider
            val controller = ProviderSessionController(registry, CapabilityDispatcher(registry, repository), repository, this, { active })
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            val oldProjection = active.request.catalog
            val read = secondDefinition.copy(readOnly = true)
            val backend =
                object : ExecutionBackend {
                    override suspend fun unavailableReason(): String? = null

                    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                        executions++
                        return ExecutionOutcome(InvocationStatus.COMPLETED, "Found Park")
                    }
                }
            registry.replace(mapOf(read.id to backend), listOf(read))
            assertEquals(oldProjection, active.request.catalog)
            controller.disconnect()
            advanceUntilIdle()
            active = FakeProvider()
            controller.connect("unused")
            advanceUntilIdle()
            assertEquals(
                listOf(read.id),
                active.request.catalog.tools
                    .map { it.capabilityId },
            )
            assertNotEquals(oldProjection.revision, active.request.catalog.revision)
            controller.submit("Find two places")
            advanceUntilIdle()
            active.call("read1")
            advanceUntilIdle()
            active.call("read2")
            advanceUntilIdle()
            assertEquals(listOf("COMPLETED", "NOT_EXECUTED"), active.results.map { it.status })
            assertEquals(1, executions)
            controller.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun `unadvertised tool is rejected without disconnecting or executing`() =
        runTest {
            val controller =
                ProviderSessionController(
                    registry,
                    CapabilityDispatcher(registry, repository),
                    repository,
                    this,
                    { provider },
                    hiddenCapabilities = { setOf(definition.id) },
                )
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            controller.submit("Open Park")
            advanceUntilIdle()
            provider.channel.send(
                ProviderEvent.ToolCallReady(
                    CallIdentity(
                        provider.connectionEpoch,
                        "session",
                        provider.input.id,
                        provider.input.id,
                        "turn",
                        provider.request.catalog.revision,
                        "hidden",
                    ),
                    definition.id,
                    Json.parseToJsonElement("""{"place":"Park"}""").jsonObject,
                ),
            )
            advanceUntilIdle()
            assertEquals("NOT_EXECUTED", provider.results.single().status)
            assertEquals(ProviderStatus.CONNECTED, controller.state.value.providerStatus)
            assertEquals(0, executions)
            controller.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun `large catalog opens within limit and overflow calls never execute`() =
        runTest {
            val definitions = List(65) { definition.copy(id = "extension.example.action_${it.toString().padStart(2, '0')}") }
            val backend =
                registry.snapshot.resolve(
                    com.colonelpanic.eva.capability.ToolProposal(
                        "lookup",
                        definition.id,
                        emptyMap(),
                        "lookup",
                        registry.snapshot.revision,
                    ),
                )!!
            val large = CapabilityRegistry(definitions.associate { it.id to backend }, definitions)
            val controller = ProviderSessionController(large, CapabilityDispatcher(large, repository), repository, this, { provider })
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            assertEquals(64, provider.request.catalog.tools.size)
            controller.submit("Use the overflow action")
            advanceUntilIdle()
            provider.channel.send(
                ProviderEvent.ToolCallReady(
                    CallIdentity(
                        provider.connectionEpoch,
                        "session",
                        provider.input.id,
                        provider.input.id,
                        "turn",
                        provider.request.catalog.revision,
                        "overflow",
                    ),
                    definitions.last().id,
                    Json.parseToJsonElement("""{"place":"Park"}""").jsonObject,
                ),
            )
            advanceUntilIdle()
            assertEquals("NOT_EXECUTED", provider.results.single().status)
            assertEquals(0, executions)
            controller.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun `disconnect preserves submitted work and journals its eventual receipt without sending to closed session`() =
        runTest {
            val submitted = kotlinx.coroutines.CompletableDeferred<Unit>()
            val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
            val backend =
                object : ExecutionBackend {
                    override suspend fun unavailableReason(): String? = null

                    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                        submitted.complete(Unit)
                        finish.await()
                        return ExecutionOutcome(InvocationStatus.COMPLETED, "Done after disconnect")
                    }
                }
            val registry = CapabilityRegistry(mapOf(definition.id to backend), listOf(definition))
            val controller = ProviderSessionController(registry, CapabilityDispatcher(registry, repository), repository, this, { provider })
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            controller.submit("Run it")
            advanceUntilIdle()
            provider.call("pending")
            runCurrent()
            submitted.await()
            controller.disconnect()
            finish.complete(Unit)
            advanceUntilIdle()
            assertEquals(InvocationStatus.COMPLETED, repository.history().single().status)
            assertEquals("Done after disconnect", repository.history().single().message)
            assertEquals(0, provider.results.size)
        }

    private class FakeProvider :
        ConversationProvider,
        ConversationSession {
        override val connectionEpoch = "epoch"
        val channel = Channel<ProviderEvent>(Channel.UNLIMITED)
        override val events = channel.receiveAsFlow()
        lateinit var request: SessionOpenRequest
        lateinit var input: ConversationInput
        val results = mutableListOf<CorrelatedToolResult>()

        override suspend fun open(request: SessionOpenRequest): ConversationSession {
            this.request = request
            channel.send(ProviderEvent.Connected("session", request.catalog.revision))
            return this
        }

        override suspend fun submit(input: ConversationInput) {
            this.input = input
        }

        override suspend fun requestResponse(request: ResponseRequest) = Unit

        override suspend fun submitToolResult(result: CorrelatedToolResult) {
            results.add(result)
        }

        override suspend fun close() {
            channel.close()
        }

        suspend fun call(id: String) {
            channel.send(
                ProviderEvent.ToolCallReady(
                    CallIdentity(connectionEpoch, "session", input.id, input.id, "turn", request.catalog.revision, id),
                    request.catalog.tools
                        .single()
                        .capabilityId,
                    Json.parseToJsonElement("""{"place":"Park"}""").jsonObject,
                ),
            )
        }
    }
}
