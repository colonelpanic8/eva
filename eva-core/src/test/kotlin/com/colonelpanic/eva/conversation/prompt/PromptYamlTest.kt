package com.colonelpanic.eva.conversation.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PromptYamlTest {
    @Test
    fun `the stock prompt survives a round trip and reads as prose`() {
        val text = PromptYaml.encode(PromptDefaults.config)
        assertEquals(PromptDefaults.config, PromptYaml.decode(text))
        // Long instructions are literal blocks, so a hand edit and a diff see lines, not escapes.
        assertTrue(text.contains("instruction: |"))
        assertFalse(text.contains("\\n"))
        // Defaults are left out, so the file says only what was chosen.
        assertFalse(text.contains("enabled: true"))
        assertTrue(text.contains("enabled: false"))
        assertFalse(text.contains("applies: both"))
        assertTrue(text.contains("applies: voice"))
        // A file under version control ends the way every other text file does.
        assertTrue(text.endsWith("\n"))
        assertFalse(text.endsWith("\n\n"))
    }

    @Test
    fun `a file written by hand parses with its defaults filled in`() {
        val config =
            PromptYaml.decode(
                """
                components:
                - id: identity
                  instruction: |
                    You are EVA.
                    Be brief.
                - id: quiet
                  title: Quiet
                  enabled: false
                  applies: voice
                  slot: call
                  instruction: Say nothing.
                  describe:
                    eva.session.end: Hang up now.
                  hide: [eva.android.messages.send]
                """.trimIndent(),
            )
        assertEquals(
            listOf(
                PromptComponent("identity", instruction = "You are EVA.\nBe brief."),
                PromptComponent(
                    "quiet",
                    title = "Quiet",
                    enabled = false,
                    applies = Applies.VOICE,
                    slot = "call",
                    instruction = "Say nothing.",
                    describe = mapOf("eva.session.end" to "Hang up now."),
                    hide = listOf("eva.android.messages.send"),
                ),
            ),
            config.components,
        )
    }

    @Test
    fun `a misspelled field is reported with where it is`() {
        try {
            PromptYaml.decode("components:\n- id: a\n  instructions: oops\n")
            fail("expected a parse error")
        } catch (error: PromptConfigException) {
            assertTrue(error.message, error.message!!.startsWith("Line 3, column 3"))
            assertTrue(error.message, error.message!!.contains("instructions"))
        }
    }
}
