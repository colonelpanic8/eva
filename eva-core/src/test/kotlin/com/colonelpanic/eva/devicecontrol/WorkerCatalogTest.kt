package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.conversation.prompt.Applies
import com.colonelpanic.eva.conversation.prompt.PromptConfig
import com.colonelpanic.eva.conversation.prompt.PromptDefaults
import com.colonelpanic.eva.conversation.prompt.Wording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerCatalogTest {
    @Test
    fun `device worker receives enabled authorization including user edits without conversation instructions`() {
        val stock = PromptDefaults.config
        val base = workerWording(Wording.bundled)
        assertEquals(base, workerWording(Wording.bundled, stock))
        val approval = stock.components.single { it.id == PromptDefaults.PREAPPROVE_ACTIONS_ID }
        val custom = approval.copy(enabled = true, instruction = "Order lunch up to $30.\nIt is {{clock}}.")
        val optedIn = stock.upsert(custom)
        val worker = workerWording(Wording.bundled, optedIn, mapOf("clock" to "noon"))
        assertEquals(base.instructions + "\n\nOrder lunch up to $30. It is noon.", worker.instructions)
        assertEquals(base.tools, worker.tools)
        assertEquals(base.notices, worker.notices)
        assertEquals(base, workerWording(Wording.bundled, optedIn.toggle(approval.id, false)))
        assertEquals(base, workerWording(Wording.bundled, optedIn.remove(approval.id)))
        assertEquals(base, workerWording(Wording.bundled, optedIn.upsert(custom.copy(applies = Applies.VOICE))))
    }

    @Test
    fun `device worker receives stock checkout and credential authorization only after opt in`() {
        val component = PromptDefaults.config.components.single { it.id == PromptDefaults.PREAPPROVE_ACTIONS_ID }
        val prompt = PromptConfig(listOf(component.copy(enabled = true)))
        val worker = workerWording(Wording.bundled, prompt)
        assertTrue(worker.instructions.contains("placing orders and completing checkout"))
        assertTrue(worker.instructions.contains("entering passwords and verification codes"))
        assertFalse(workerWording(Wording.bundled).instructions.contains("standing authorization"))
    }
}
