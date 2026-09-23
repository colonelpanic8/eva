package com.colonelpanic.eva.conversation.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptSourceTest {
    private fun config(vararg components: PromptComponent) = PromptConfig(components.toList())

    @Test
    fun `following a source updates untouched wording and keeps what the user made their own`() {
        val baseline =
            config(
                PromptComponent("identity", instruction = "Old identity"),
                PromptComponent("style", instruction = "Old style"),
                PromptComponent("dropped", instruction = "Going away"),
                PromptComponent("dropped-edited", instruction = "Going away too"),
                PromptComponent("deleted", instruction = "User removed this"),
            )
        val current =
            config(
                PromptComponent("identity", enabled = false, instruction = "Old identity"),
                PromptComponent("mine", instruction = "Added by the user"),
                PromptComponent("style", instruction = "My style"),
                PromptComponent("dropped", instruction = "Going away"),
                PromptComponent("dropped-edited", instruction = "I kept this"),
            )
        val remote =
            config(
                PromptComponent("identity", instruction = "New identity"),
                PromptComponent("style", instruction = "New style"),
                PromptComponent("deleted", instruction = "Reworded"),
                PromptComponent("arrived", instruction = "Brand new"),
            )

        val followed = followSource(current, baseline, remote)

        assertEquals(listOf("identity", "mine", "style", "dropped-edited", "arrived"), followed.components.map { it.id })
        val byId = followed.components.associateBy { it.id }
        assertEquals("New identity", byId.getValue("identity").instruction)
        assertFalse(byId.getValue("identity").enabled)
        assertEquals("My style", byId.getValue("style").instruction)
        assertEquals("Brand new", byId.getValue("arrived").instruction)
        assertEquals("I kept this", byId.getValue("dropped-edited").instruction)
    }

    @Test
    fun `a component arriving in a slot stays off beside the user's choice`() {
        val current = config(PromptComponent("open", slot = "call"), PromptComponent("one", slot = "call", enabled = false))
        val remote =
            config(
                PromptComponent("one", slot = "call"),
                PromptComponent("open", slot = "call", enabled = false),
                PromptComponent("new", slot = "call"),
            )

        val followed = followSource(current, current, remote)

        assertEquals(listOf("open"), followed.components.filter { it.enabled }.map { it.id })
        assertTrue(followed.problem(emptySet()) == null)
    }

    @Test
    fun `an installation that never followed its source treats the shipped copy as its baseline`() {
        val stock = PromptDefaults.config
        val remote =
            stock.copy(
                components =
                    stock.components.map {
                        if (it.id ==
                            "identity"
                        ) {
                            it.copy(instruction = "Reworded upstream")
                        } else {
                            it
                        }
                    },
            )

        val followed = followSource(stock, PromptDefaults.config, remote)

        assertEquals("Reworded upstream", followed.components.first { it.id == "identity" }.instruction)
    }
}
