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
