package com.colonelpanic.eva.conversation.prompt

import com.colonelpanic.eva.capability.BundledCapabilities
import com.colonelpanic.eva.conversation.ThreadController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordingTest {
    @Test
    fun `the shipped wording names only real tools and parameters and covers every note`() {
        val tools =
            (
                BundledCapabilities.definitions.map { it.id to it.inputSchema } +
                    (PromptDefaults.END_CONVERSATION_ID to ThreadController.END_CONVERSATION.inputSchema)
            ).toMap()
        Wording.bundled.tools.forEach { (id, text) ->
            val schema = tools[id]
            assertTrue("$id is not one of EVA's tools", schema != null)
            val declared = (schema!!["properties"] as Map<*, *>).keys
            text.parameters.keys.forEach { assertTrue("$id has no parameter $it", it in declared) }
        }
        BundledCapabilities.definitions.forEach { assertTrue("${it.id} has no description", it.description.isNotBlank()) }
        assertTrue(Wording.bundled.message(Wording.CONTINUATION).isNotBlank())
        assertTrue(Wording.bundled.message(Wording.HANG_UP_DEFERRED).isNotBlank())
    }

    @Test
    fun `followed wording rewords a tool and its parameters and falls back for missing notes`() {
        val followed =
            Wording.decode(
                """
                tools:
                  eva.android.phone.dial:
                    description: Dial it.
                    parameters:
                      number: The number, digits only.
                """.trimIndent(),
            )
        val dial = BundledCapabilities.definitions.first { it.id == "eva.android.phone.dial" }
        val tool =
            followed.describe(
                com.colonelpanic.eva.providers
                    .ProviderToolDefinition(dial.id, dial.title, dial.description, dial.inputSchema),
            )

        assertEquals("Dial it.", tool.description)
        assertTrue(tool.inputSchema.toString().contains("The number, digits only."))
        assertEquals(Wording.bundled.message(Wording.CONTINUATION), followed.message(Wording.CONTINUATION))
    }
}
