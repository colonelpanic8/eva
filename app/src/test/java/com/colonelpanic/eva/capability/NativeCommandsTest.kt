package com.colonelpanic.eva.capability

import com.colonelpanic.eva.conversation.LocalCommandProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeCommandsTest {
    private val received = mutableListOf<Pair<String, Map<String, String>>>()
    private val registry =
        CapabilityRegistry(
            listOf(CapabilityRegistry.MAP_SEARCH, CapabilityRegistry.NAVIGATE, CapabilityRegistry.SMS_COMPOSE).associateWith { id ->
                object : ExecutionBackend {
                    override suspend fun unavailableReason(): String? = null

                    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                        received += id to arguments
                        return ExecutionOutcome(InvocationStatus.HANDED_OFF, "Opened")
                    }
                }
            },
        )
    private val dispatcher = CapabilityDispatcher(registry, MemoryInvocationRepository())
    private val provider = LocalCommandProvider()

    @Test
    fun `typed commands dispatch to the matching backend with distinct arguments`() =
        runTest {
            val commands = listOf("map Park", "navigate to 1 Ferry Building", "text +1 (202) 555-0100: Hello: meet at 5 & bring café?")
            commands.forEachIndexed { index, command ->
                val proposal = checkNotNull(provider.propose("call:$index", command))
                dispatcher.execute(proposal)
                dispatcher.execute(proposal)
            }
            assertEquals(
                listOf(
                    CapabilityRegistry.MAP_SEARCH to mapOf("destination" to "Park"),
                    CapabilityRegistry.NAVIGATE to mapOf("destination" to "1 Ferry Building"),
                    CapabilityRegistry.SMS_COMPOSE to
                        mapOf("recipient" to "+1 (202) 555-0100", "message" to "Hello: meet at 5 & bring café?"),
                ),
                received,
            )
        }

    @Test
    fun `message recipient injection multiple recipients and empty bodies are rejected`() =
        runTest {
            val arguments =
                listOf(
                    mapOf("recipient" to "2025550100;2025550101", "message" to "Hello"),
                    mapOf("recipient" to "2025550100?body=Injected", "message" to "Hello"),
                    mapOf("recipient" to "2025550100,2025550101", "message" to "Hello"),
                    mapOf("recipient" to "Kat", "message" to "Hello"),
                    mapOf("recipient" to "2025550100", "message" to ""),
                    mapOf("recipient" to "2025550100", "message" to "a".repeat(801)),
                    mapOf("recipient" to "2025550100", "message" to "Hello\u0000world"),
                    mapOf("recipient" to "2025550100", "message" to "Hello", "send" to "true"),
                )
            arguments.forEachIndexed { index, args ->
                val result = dispatcher.execute(ToolProposal("invalid:$index", CapabilityRegistry.SMS_COMPOSE, args, "text test"))
                assertEquals(InvocationStatus.NOT_EXECUTED, result.status)
            }
            assertEquals(emptyList<Pair<String, Map<String, String>>>(), received)
        }

    @Test
    fun `navigation does not accept route parameters disguised as arguments`() =
        runTest {
            val proposal =
                ToolProposal(
                    "navigation",
                    CapabilityRegistry.NAVIGATE,
                    mapOf("destination" to "Park", "mode" to "b"),
                    "navigate to Park",
                )
            assertEquals(InvocationStatus.NOT_EXECUTED, dispatcher.execute(proposal).status)
            assertEquals(0, received.size)
        }
}
