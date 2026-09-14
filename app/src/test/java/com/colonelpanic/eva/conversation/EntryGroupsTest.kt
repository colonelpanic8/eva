package com.colonelpanic.eva.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class EntryGroupsTest {
    private fun turn(id: String) = ConversationEntry(id, "request $id", "answer", EntryStatus.ANSWER)

    private fun action(
        id: String,
        parent: String,
    ) = ConversationEntry(id, "", "done", EntryStatus.COMPLETED, parentId = parent)

    @Test
    fun `actions nest under the turn that ran them, in order`() {
        val groups = groups(listOf(turn("t1"), action("a1", "t1"), action("a2", "t1"), turn("t2"), action("a3", "t2")))
        assertEquals(listOf("t1", "t2"), groups.map { it.entry.id })
        assertEquals(listOf("a1", "a2"), groups[0].actions.map { it.id })
        assertEquals(listOf("a3"), groups[1].actions.map { it.id })
    }

    @Test
    fun `an action whose turn is gone stands on its own`() {
        val groups = groups(listOf(action("a1", "aged-out"), turn("t2")))
        assertEquals(listOf("a1", "t2"), groups.map { it.entry.id })
        assertEquals(emptyList<ConversationEntry>(), groups[0].actions)
    }
}
