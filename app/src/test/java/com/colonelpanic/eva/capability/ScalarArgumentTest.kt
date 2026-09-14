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
        assertNull(registry.validationError(proposal(CapabilityRegistry.SET_ALARM, mapOf("hour" to "7", "minute" to "30"))))
        assertNull(registry.validationError(proposal(CapabilityRegistry.SET_TIMER, mapOf("seconds" to "600"))))
    }

    @Test
    fun `out of range and non numeric integers are rejected`() {
        for (arguments in listOf(
            mapOf("hour" to "24", "minute" to "0"),
            mapOf("hour" to "7", "minute" to "60"),
            mapOf("hour" to "seven", "minute" to "0"),
            mapOf("hour" to "7.5", "minute" to "0"),
            mapOf("hour" to "7"),
        )) {
            assertNotNull("$arguments should be rejected", registry.validationError(proposal(CapabilityRegistry.SET_ALARM, arguments)))
        }
    }

    @Test
    fun `optional properties may be omitted but unknown ones may not`() {
        assertNull(registry.validationError(proposal(CapabilityRegistry.CALENDAR_EVENT, mapOf("title" to "Standup"))))
        assertNotNull(
            registry.validationError(proposal(CapabilityRegistry.CALENDAR_EVENT, mapOf("title" to "Standup", "guest" to "Sam"))),
        )
    }

    @Test
    fun `enumerated and format constrained arguments are enforced`() {
        assertNull(registry.validationError(proposal(CapabilityRegistry.OPEN_SETTINGS, mapOf("screen" to "wifi"))))
        assertNotNull(registry.validationError(proposal(CapabilityRegistry.OPEN_SETTINGS, mapOf("screen" to "camera"))))
        assertNotNull(registry.validationError(proposal(CapabilityRegistry.OPEN_URL, mapOf("url" to "javascript:alert(1)"))))
        assertNull(registry.validationError(proposal(CapabilityRegistry.OPEN_URL, mapOf("url" to "https://example.com"))))
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
