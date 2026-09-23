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
        assertTrue(voice.instructions.contains("give only a brief confirmation"))
        assertTrue(voice.instructions.contains("This call is for one request."))
        assertTrue(voice.instructions.contains("every closing line must be accompanied by that tool call"))
        assertFalse(voice.instructions.contains("This call stays open"))
        assertFalse(typed.instructions.contains("spoken conversation"))
        assertFalse(typed.instructions.contains("This call is for one request."))
        assertTrue(typed.instructions.contains("Never claim sending a message"))
        assertFalse(
            PromptDefaults.config
                .toggle("brief-actions", false)
                .assemble(PromptContext(voice = true, variables))
                .instructions
                .contains("brief confirmation"),
        )
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
        assertTrue(open.assemble(PromptContext(voice = true, variables)).instructions.contains("This call stays open"))

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
    fun `selecting a call mode switches the call slot and is reported by the assembled prompt`() {
        val oneRequest = PromptDefaults.config.selectCallMode(VoiceCallMode.ONE_REQUEST)
        val open = PromptDefaults.config.selectCallMode(VoiceCallMode.OPEN_CONVERSATION)
        assertTrue(oneRequest.assemble(PromptContext(true, variables)).instructions.contains("This call is for one request."))
        assertTrue(open.assemble(PromptContext(true, variables)).instructions.contains("This call stays open"))
        assertEquals(VoiceCallMode.OPEN_CONVERSATION, open.assemble(PromptContext(true, variables)).callMode)
        assertNull(open.assemble(PromptContext(false, variables)).callMode)
        assertNull(open.toggle(PromptDefaults.OPEN_CONVERSATION_ID, false).callMode)
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
        val endDescription = stock.first { it.capabilityId == end.capabilityId }.description
        assertTrue(endDescription.contains("same response"))
        assertTrue(endDescription.contains("spoken goodbye without this tool leaves the call open", ignoreCase = true))
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
    fun `stock call wording is upgraded without replacing custom wording`() {
        val legacyInstruction =
            """
            This call is for one request. Once you have finished it, because the result is reported, the
            question is answered, or you have said what you could not do, say a short closing line and
            end the conversation with its tool. Do not ask whether there is anything else. Stay on only
            while something is genuinely unfinished: an action is still running, or you asked the user a
            question and are waiting for the answer. If the user asks you to stay on the line or starts
            another request, keep going and treat that as the request to finish.
            """.trimIndent()
        val legacyDescription =
            """
            Hang up this voice conversation; your goodbye finishes playing before the call ends.
            Call it as soon as the user's request is complete and nothing is outstanding, after a
            short spoken closing line. Do not call it while an action is unfinished, while you
            are waiting for the user to answer a question, or after the user has asked you to
            stay on the line.
            """.trimIndent()
        val custom = PromptComponent("custom", instruction = "Keep my instructions.")
        val legacy =
            PromptConfig(
                listOf(
                    PromptComponent(
                        "one-request",
                        instruction = legacyInstruction,
                        describe = mapOf(PromptDefaults.END_CONVERSATION_ID to legacyDescription),
                    ),
                    custom,
                ),
            )

        val upgraded = PromptDefaults.upgradeStockCallWording(legacy)

        val call = upgraded.components.first()
        assertTrue(call.instruction.contains("same response"))
        assertTrue(call.describe.getValue(PromptDefaults.END_CONVERSATION_ID).contains("leaves the call open"))
        assertEquals(custom, upgraded.components.last())

        val customized =
            legacy.copy(
                components =
                    legacy.components.map {
                        if (it.id == "one-request") {
                            it.copy(
                                instruction = "My call policy.",
                                describe = mapOf(PromptDefaults.END_CONVERSATION_ID to "My hang-up policy."),
                            )
                        } else {
                            it
                        }
                    },
            )
        assertEquals(customized, PromptDefaults.upgradeStockCallWording(customized))
    }

    @Test
    fun `stock open-conversation wording no longer lets the model hang up on its own judgment`() {
        val previous =
            PromptComponent(
                PromptDefaults.OPEN_CONVERSATION_ID,
                instruction =
                    """
                    This call stays open. Finishing a request is not a reason to hang up: say what happened and
                    wait for the user. Only when the user says goodbye, says that is all, or asks you to hang up,
                    say a brief goodbye and call the end-conversation tool in the same response. Saying goodbye
                    without the tool does not end the call.
                    """.trimIndent(),
                describe =
                    mapOf(
                        PromptDefaults.END_CONVERSATION_ID to
                            """
                            Hang up this voice conversation. When the user says goodbye, says they are done, or asks
                            you to hang up, say a brief goodbye and call this tool in the same response. A spoken
                            goodbye without this tool leaves the call open. A finished request is not by itself a
                            reason to call it; the user decides when the call ends.
                            """.trimIndent(),
                    ),
            )

        val upgraded = PromptDefaults.upgradeStockCallWording(PromptConfig(listOf(previous))).components.single()

        val stock = PromptDefaults.config.components.first { it.id == PromptDefaults.OPEN_CONVERSATION_ID }
        assertEquals(stock.instruction, upgraded.instruction)
        assertEquals(stock.describe, upgraded.describe)
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
