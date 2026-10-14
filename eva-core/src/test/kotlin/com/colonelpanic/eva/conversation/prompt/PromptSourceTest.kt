package com.colonelpanic.eva.conversation.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptSourceTest {
    private fun config(vararg components: PromptComponent) = PromptConfig(components.toList())

    @Test
    fun `preapproval arrives disabled and following updates preserves opt in and revocation`() {
        val stock = PromptDefaults.config
        val id = PromptDefaults.PREAPPROVE_ACTIONS_ID
        val old = stock.remove(id)
        val arrived = followSource(old, old, stock)
        assertFalse(arrived.components.single { it.id == id }.enabled)
        val remote = stock.upsert(stock.components.single { it.id == id }.copy(instruction = "Updated authorization."))
        val optedIn = followSource(arrived.toggle(id, true), stock, remote)
        assertTrue(optedIn.components.single { it.id == id }.enabled)
        assertEquals("Updated authorization.", optedIn.components.single { it.id == id }.instruction)
        val revoked = followSource(optedIn.toggle(id, false), remote, stock)
        assertFalse(revoked.components.single { it.id == id }.enabled)
        assertFalse(followSource(optedIn.remove(id), remote, stock).components.any { it.id == id })
    }

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
