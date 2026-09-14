package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.capability.InvocationRecord
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.providers.HistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadProjectionTest {
    private val thread = "t"

    private fun user(
        turn: String?,
        text: String,
        at: Long,
    ) = ThreadItem.UserMessage("u$at", thread, turn, at, text, spoken = false)

    private fun assistant(
        turn: String?,
        text: String,
        at: Long,
    ) = ThreadItem.AssistantMessage("a$at", thread, turn, at, text, spoken = false)

    private fun call(
        turn: String,
        callId: String,
        at: Long,
    ) = ThreadItem.ActionCall("c$at", thread, turn, at, callId, "eva.maps.search", "Search maps", mapOf("destination" to "Park"))

    private fun receipt(
        callId: String,
        status: InvocationStatus,
        message: String,
    ) = InvocationRecord(callId, "fp", "map Park", "Park", status, message, 0, "eva.maps.search", 1)

    @Test
    fun `a turn is one entry with its actions beneath and its answer joined`() {
        val turns = listOf(Turn("turn-1", thread, "map Park", TurnStatus.ANSWERED, 1))
        val items =
            listOf(
                ThreadItem.Notice("n0", thread, null, 0, NoticeKind.SESSION_STARTED, "Text session"),
                user("turn-1", "map Park", 1),
                call("turn-1", "call-1", 2),
                assistant("turn-1", "Opened the park.", 3),
            )
        val entries = projectEntries(turns, items, mapOf("call-1" to receipt("call-1", InvocationStatus.HANDED_OFF, "Opened Park")))
        assertEquals(listOf("n0", "turn-1", "call-1"), entries.map { it.id })
        assertEquals(EntryStatus.SESSION, entries[0].status)
        val turn = entries[1]
        assertEquals("map Park", turn.request)
        assertEquals("Opened the park.", turn.response)
        assertEquals(EntryStatus.ANSWER, turn.status)
        val action = entries[2]
        assertEquals("turn-1", action.parentId)
        assertEquals(EntryStatus.HANDED_OFF, action.status)
        assertEquals("Search maps", action.actionTitle)
    }

    @Test
    fun `an open turn is pending and an action without a receipt is preparing`() {
        val turns = listOf(Turn("turn-1", thread, "map Park", TurnStatus.OPEN, 1))
        val entries = projectEntries(turns, listOf(user("turn-1", "map Park", 1), call("turn-1", "call-1", 2)), emptyMap())
        assertEquals(EntryStatus.PENDING, entries[0].status)
        assertEquals("Working on it…", entries[0].response)
        assertEquals("Preparing action…", entries[1].response)
    }

    @Test
    fun `a spoken transcript with no turn stands alone`() {
        val entries = projectEntries(emptyList(), listOf(user(null, "hello", 1), assistant(null, "hi", 2)), emptyMap())
        assertEquals(listOf("u1", "a2"), entries.map { it.id })
        assertEquals("hello", entries[0].request)
        assertEquals("hi", entries[1].response)
    }

    @Test
    fun `history is bounded from the end and attributes receipts as evidence`() {
        val items = (1..45).map { user("turn-$it", "message $it", it.toLong()) } + call("turn-45", "call-1", 46)
        val history = projectHistory(items, mapOf("call-1" to receipt("call-1", InvocationStatus.COMPLETED, "Done")))
        assertEquals(HISTORY_ITEM_LIMIT + 1, history.size)
        assertTrue((history.first() as HistoryItem.Note).text.startsWith("6 earlier items"))
        val evidence = history.last() as HistoryItem.ActionEvidence
        assertEquals("Search maps", evidence.title)
        assertEquals("COMPLETED", evidence.status)
        assertEquals("Done", evidence.message)
    }
}
