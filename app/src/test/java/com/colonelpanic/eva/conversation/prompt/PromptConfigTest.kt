package com.colonelpanic.eva.conversation.prompt

import com.colonelpanic.eva.providers.ProviderToolDefinition
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptConfigTest {
    private val variables = mapOf("clock" to "It is noon.", "lookup_retries" to "3")

    @Test
    fun `a session gets the enabled components written for it, in file order`() {
        val voice = PromptDefaults.config.assemble(PromptContext(voice = true, variables))
        val typed = PromptDefaults.config.assemble(PromptContext(voice = false, variables))
        val voiceParagraphs = voice.instructions.split("\n\n")
        assertTrue(voiceParagraphs.first().startsWith("You are EVA"))
        assertEquals("It is noon.", voiceParagraphs.last())
        assertTrue(voice.instructions.contains("This is a spoken conversation."))
        assertTrue(voice.instructions.contains("This call is for one request."))
        assertFalse(voice.instructions.contains("This call stays open."))
        assertFalse(typed.instructions.contains("spoken conversation"))
        assertFalse(typed.instructions.contains("This call is for one request."))
        assertTrue(typed.instructions.contains("Never claim sending a message"))
        assertNull(PromptDefaults.config.problem(PromptDefaults.VARIABLES))
    }

    @Test
    fun `variables are substituted and wrapped lines become one paragraph`() {
        val config =
            PromptConfig(
                listOf(
                    PromptComponent(
                        id = "a",
                        instruction = "Make up to {{ lookup_retries }} lookups,\n  wrapped here.\n\nSecond   paragraph.\n",
                    ),
                ),
            )
        assertEquals(
            "Make up to 3 lookups, wrapped here.\n\nSecond paragraph.",
            config.assemble(PromptContext(voice = true, variables)).instructions,
        )
    }

    @Test
    fun `turning on a slot member turns its alternatives off`() {
        val open = PromptDefaults.config.toggle("open-conversation", true)
        assertTrue(open.components.first { it.id == "open-conversation" }.enabled)
        assertFalse(open.components.first { it.id == "one-request" }.enabled)
        assertNull(open.problem(PromptDefaults.VARIABLES))
        assertTrue(open.assemble(PromptContext(voice = true, variables)).instructions.contains("This call stays open."))

        val neither = open.toggle("open-conversation", false)
        assertFalse(neither.components.first { it.id == "open-conversation" }.enabled)
        assertFalse(neither.components.first { it.id == "one-request" }.enabled)
        // Turning something off, or toggling outside a slot, leaves the others alone.
        assertTrue(
            neither
                .toggle("clock", false)
                .components
                .first { it.id == "identity" }
                .enabled,
        )
    }

    @Test
    fun `the problems a hand edit can introduce are named`() {
        fun problem(vararg components: PromptComponent) = PromptConfig(components.toList()).problem(setOf("clock"))
        assertTrue(problem(PromptComponent("a"), PromptComponent("a"))!!.contains("“a”"))
        assertTrue(problem(PromptComponent("Bad Id"))!!.contains("not a valid id"))
        assertTrue(problem(PromptComponent("a", instruction = "{{time}}"))!!.contains("{{time}}"))
        assertTrue(problem(PromptComponent("a", slot = "s"), PromptComponent("b", slot = "s"))!!.contains("slot “s”"))
        assertNull(problem(PromptComponent("a", slot = "s"), PromptComponent("b", slot = "s", enabled = false)))
        assertNull(problem(PromptComponent("a/b-1", instruction = "{{clock}}")))
    }

    @Test
    fun `active components reword and withhold tools`() {
        val end = ProviderToolDefinition("eva.session.end", "End", "stock", buildJsonObject {})
        val send = ProviderToolDefinition("eva.android.messages.send", "Send", "sends", buildJsonObject {})
        val stock = PromptDefaults.config.assemble(PromptContext(voice = true, variables)).apply(listOf(end, send))
        assertTrue(stock.first { it.capabilityId == end.capabilityId }.description.contains("as soon as the user's request is complete"))
        assertEquals("sends", stock.first { it.capabilityId == send.capabilityId }.description)

        val readOnly =
            PromptDefaults.config
                .upsert(PromptComponent("read-only", hide = listOf(send.capabilityId), describe = mapOf("eva.nothing" to "ignored")))
                .assemble(PromptContext(voice = true, variables))
                .apply(listOf(end, send))
        assertEquals(listOf(end.capabilityId), readOnly.map { it.capabilityId })

        // A disabled component's adjustments do not apply.
        val off =
            PromptDefaults.config
                .toggle("one-request", false)
                .assemble(PromptContext(voice = true, variables))
                .apply(listOf(end))
        assertEquals("stock", off.single().description)
    }

    @Test
    fun `upsert replaces in place and remove drops`() {
        val edited = PromptDefaults.config.upsert(PromptComponent("clock", title = "Time", instruction = "{{clock}}"))
        assertEquals(PromptDefaults.config.components.size, edited.components.size)
        assertEquals("Time", edited.components.last().title)
        assertEquals(
            "added",
            PromptDefaults.config
                .upsert(PromptComponent("added"))
                .components
                .last()
                .id,
        )
        assertFalse(
            PromptDefaults.config
                .remove("clock")
                .components
                .any { it.id == "clock" },
        )
    }
}
