package com.colonelpanic.eva.adapters.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSummariesTest {
    private val now = 1_700_000_000_000L
    private val alice = ConversationParticipant("+12025550100", "Alice Smith")
    private val bob = ConversationParticipant("+12025550101", "Bob")
    private val unknown = ConversationParticipant("+12025550102")
    private val group = Conversation(7, listOf(alice, bob), now - 2 * HOUR, "See you then")

    @Test
    fun `a listed conversation carries the id a later send needs`() {
        assertEquals(
            "Recent conversations: 7 — Alice Smith, Bob (group of 2) — 2 hours ago — \"See you then\". " +
                ConversationSummaries.USE_THE_ID,
            ConversationSummaries.describeConversations(null, listOf(group), now),
        )
    }

    @Test
    fun `a participant without a contact entry is still addressable by number`() {
        val summary =
            ConversationSummaries.describeConversations(
                "555",
                listOf(Conversation(9, listOf(unknown), now - 45 * MINUTE)),
                now,
            )
        assertEquals(
            "Conversations matching \"555\": 9 — +12025550102 — 45 minutes ago. " + ConversationSummaries.USE_THE_ID,
            summary,
        )
    }

    @Test
    fun `an empty result says so instead of inviting a retry with the same words`() {
        assertEquals(
            "No conversation matches \"climbing\".",
            ConversationSummaries.describeConversations("climbing", emptyList(), now),
        )
    }

    @Test
    fun `reading a thread names each speaker and puts the newest message last`() {
        val messages =
            listOf(
                ConversationMessage(incoming = true, sender = alice, sentMillis = now - 2 * HOUR, body = "Are we on?"),
                ConversationMessage(incoming = false, sender = null, sentMillis = now - 30 * SECOND, body = "Yes\n see you"),
            )
        assertEquals(
            "Conversation 7 with Alice Smith, Bob, oldest message first: " +
                "[2 hours ago] Alice Smith: Are we on? | [just now] You: Yes see you. " + ConversationSummaries.PARTIAL,
            ConversationSummaries.describeMessages(group, messages, now),
        )
    }

    @Test
    fun `an old thread is read back from its newest end`() {
        val total = ConversationSummaries.MAX_MESSAGES + 5
        val messages =
            (1..total).map { index ->
                ConversationMessage(true, alice, now - (total - index) * MINUTE, "message $index")
            }
        val summary = ConversationSummaries.describeMessages(group, messages, now)
        assertTrue(summary.contains("message $total"))
        assertTrue(!summary.contains("message 5 "))
    }

    private companion object {
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND
        const val HOUR = 60 * MINUTE
    }
}
