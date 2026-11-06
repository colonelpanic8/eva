package com.colonelpanic.eva.capability

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPreferenceCapabilitiesTest {
    private val saved = mutableMapOf<String, ToolPreferences>()
    private val store =
        object : ToolPreferenceStore {
            override fun preferences() = saved.toMap()

            override fun save(
                capabilityId: String,
                preferences: ToolPreferences?,
            ) {
                if (preferences == null) saved.remove(capabilityId) else saved[capabilityId] = preferences
            }
        }
    private val backend = ToolPreferenceCapabilities.backend(store) { mapOf(TestCapabilities.SEARCH to TestCapabilities.search) }

    @Test
    fun `defaults are merged, cleared, and removed when nothing is left`() =
        runTest {
            val set =
                backend.execute(
                    mapOf("capabilityId" to TestCapabilities.SEARCH, "defaults" to """{"destination":"Home"}""", "confirm" to "always"),
                )
            assertEquals(InvocationStatus.COMPLETED, set.status)
            assertEquals(ToolPreferences(ConfirmLevel.ALWAYS, mapOf("destination" to "Home")), saved[TestCapabilities.SEARCH])
            backend.execute(mapOf("capabilityId" to TestCapabilities.SEARCH, "clear" to """["destination"]""", "confirm" to "declared"))
            assertTrue(TestCapabilities.SEARCH !in saved)
        }

    @Test
    fun `unknown actions and parameters save nothing and say what exists`() =
        runTest {
            val unknown = backend.execute(mapOf("capabilityId" to "nope"))
            assertEquals(InvocationStatus.NOT_EXECUTED, unknown.status)
            val parameter = backend.execute(mapOf("capabilityId" to TestCapabilities.SEARCH, "defaults" to """{"origin":"Work"}"""))
            assertEquals(InvocationStatus.NOT_EXECUTED, parameter.status)
            assertTrue(parameter.message.contains("destination"))
            assertTrue(saved.isEmpty())
        }
}
