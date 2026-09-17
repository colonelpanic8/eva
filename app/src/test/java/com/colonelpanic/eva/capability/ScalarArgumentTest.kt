package com.colonelpanic.eva.capability

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Backends receive flat string arguments, so the dispatcher's independent
 * revalidation has to restore each declared scalar type before checking a
 * capability whose schema uses integers.
 */
class ScalarArgumentTest {
    private val registry =
        CapabilityRegistry(
            BundledCapabilities.definitions.associate { it.id to RecordingBackend() },
        )

    private fun proposal(
        id: String,
        arguments: Map<String, String>,
    ) = ToolProposal("call", id, arguments, "request", registry.snapshot.revision)

    @Test
    fun `integer arguments survive the string round trip`() {
        assertNull(registry.validationError(proposal(CapabilityRegistry.CONVERSATION_READ, mapOf("conversationId" to "12"))))
        assertNull(
            registry.validationError(
                proposal(CapabilityRegistry.CONVERSATION_READ, mapOf("conversationId" to "12", "limit" to "25")),
            ),
        )
    }

    @Test
    fun `out of range and non numeric integers are rejected`() {
        for (arguments in listOf(
            mapOf("conversationId" to "0"),
            mapOf("conversationId" to "-1"),
            mapOf("conversationId" to "12", "limit" to "0"),
            mapOf("conversationId" to "12", "limit" to "26"),
            mapOf("conversationId" to "twelve"),
            mapOf("conversationId" to "12.5"),
        )) {
            assertNotNull(
                "$arguments should be rejected",
                registry.validationError(proposal(CapabilityRegistry.CONVERSATION_READ, arguments)),
            )
        }
    }

    @Test
    fun `optional properties may be omitted but unknown ones may not`() {
        assertNull(registry.validationError(proposal(CapabilityRegistry.CONVERSATION_READ, mapOf("conversationId" to "12"))))
        assertNotNull(
            registry.validationError(
                proposal(CapabilityRegistry.CONVERSATION_READ, mapOf("conversationId" to "12", "unread" to "yes")),
            ),
        )
    }

    @Test
    fun `enumerated and format constrained arguments are enforced`() {
        assertNull(registry.validationError(proposal(CapabilityRegistry.MEDIA_CONTROL, mapOf("action" to "pause"))))
        assertNotNull(registry.validationError(proposal(CapabilityRegistry.MEDIA_CONTROL, mapOf("action" to "rewind"))))
        assertNotNull(registry.validationError(proposal(CapabilityRegistry.DIAL, mapOf("number" to "call mom"))))
        assertNull(registry.validationError(proposal(CapabilityRegistry.DIAL, mapOf("number" to "+12025550100"))))
    }

    @Test
    fun `every bundled capability is described by a checkable schema`() =
        runTest {
            assertEquals(BundledCapabilities.definitions.size, registry.catalog.size)
            registry.catalog.forEach { ToolSchema.check(it.inputSchema) }
        }

    private class RecordingBackend : ExecutionBackend {
        override suspend fun unavailableReason(): String? = null

        override suspend fun execute(arguments: Map<String, String>) = ExecutionOutcome(InvocationStatus.HANDED_OFF, "done")
    }
}
