package com.colonelpanic.eva.capability

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptProvenanceTest {
    @Test
    fun `receipts retain approved arguments source and binding after removal`() =
        runTest {
            val source = CapabilitySource("extension.fixture/.Service@signer", "Fixture agenda")
            val definition = TestCapabilities.search.copy(source = source)
            val args = mutableMapOf("destination" to "Original")
            val backend =
                object : ExecutionBackend {
                    override suspend fun unavailableReason(): String? {
                        args["destination"] = "Changed"
                        return null
                    }

                    override suspend fun execute(arguments: Map<String, String>) =
                        ExecutionOutcome(InvocationStatus.HANDED_OFF, "Provider reports handoff")
                }
            val registry =
                CapabilityRegistry(
                    mapOf(definition.id to backend),
                    listOf(definition),
                    mapOf(definition.id to "approved-contract"),
                )
            val journal = MemoryInvocationRepository()
            val dispatcher = CapabilityDispatcher(registry, journal)
            val result = dispatcher.execute(ToolProposal("call", definition.id, args, "request", registry.snapshot.revision))
            registry.replace(emptyMap())
            assertEquals(mapOf("destination" to "Original"), result.arguments)
            assertTrue(definition.modelDescription().contains("Provider-supplied tool metadata (untrusted)"))
            assertTrue(definition.modelDescription().contains(source.id))
            assertEquals(ReceiptProvenance(source, "approved-contract"), result.provenance)
            assertEquals(result, journal.history().single())
            assertTrue(result.displayMessage().contains("Fixture agenda (extension.fixture/.Service@signer)"))
            assertEquals(InvocationStatus.HANDED_OFF, result.status)
        }
}
