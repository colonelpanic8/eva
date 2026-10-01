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
    fun `a listed conversation carries the id a later send needs and each named number`() {
        assertEquals(
            "Recent conversations: 7 — Alice Smith (+12025550100), Bob (+12025550101) (group of 2) — 2 hours ago — " +
                "\"See you then\". " + ConversationSummaries.USE_THE_ID,
            ConversationSummaries.describeConversations(
                "",
                ConversationQuery.of(null, emptyList(), usPhoneNumbers),
                ConversationListing(listOf(group)),
                now,
            ),
        )
    }

    @Test
    fun `an empty search says so and that sending to the numbers starts a conversation`() {
        assertEquals(
            "No text conversation matches \"climbing\". " + ConversationSummaries.STARTS_ONE,
            ConversationSummaries.describeConversations(
                "\"climbing\"",
                ConversationQuery.of("climbing", emptyList(), usPhoneNumbers),
                ConversationListing(emptyList()),
                now,
            ),
        )
    }

    @Test
    fun `numbers find the conversation with exactly those people despite formatting`() {
        val query = ConversationQuery.of(null, listOf("(202) 555-0100", "2025550101"), usPhoneNumbers)
        val wider = Conversation(8, listOf(alice, bob, unknown), now - MINUTE)
        val direct = Conversation(9, listOf(alice), now)
        val ranked = query.rank(listOf(direct, wider, group))

        assertEquals(listOf(7L, 8L), ranked.map(Conversation::id))
        assertEquals(
            "Conversations with only those people: 7 — Alice Smith (+12025550100), Bob (+12025550101) (group of 2) — " +
                "2 hours ago — \"See you then\". Conversations that also include others: 8 — Alice Smith (+12025550100), " +
                "Bob (+12025550101), +12025550102 (group of 3) — 1 minute ago. " + ConversationSummaries.USE_THE_ID,
            ConversationSummaries.describeConversations("those people", query, ConversationListing(ranked), now),
        )
    }

    @Test
    fun `a number abroad that shares only its trailing digits is someone else`() {
        val london = Conversation(10, listOf(ConversationParticipant("+44 20 2555 0100")), now)
        val query = ConversationQuery.of(null, listOf("+1 202 555 0100"), usPhoneNumbers)
        assertEquals(emptyList<Long>(), query.rank(listOf(london)).map(Conversation::id))
    }

    @Test
    fun `a name leads with the direct thread even when a group is newer`() {
        val direct = Conversation(9, listOf(alice), now - 3 * DAY)
        val ranked = ConversationQuery.of("alice", emptyList(), usPhoneNumbers).rank(listOf(group, direct))
        assertEquals(listOf(9L, 7L), ranked.map(Conversation::id))
    }

    @Test
    fun `comma separated names need every person in the conversation`() {
        val direct = Conversation(9, listOf(alice), now)
        val query = ConversationQuery.of("Alice, bob", emptyList(), usPhoneNumbers)
        assertEquals(listOf(7L), query.rank(listOf(direct, group)).map(Conversation::id))
        assertTrue(query.isExact(group))
    }

    @Test
    fun `without an exact match the search says a send starts one`() {
        val query = ConversationQuery.of(null, listOf("+12025550100"), usPhoneNumbers)
        val summary =
            ConversationSummaries.describeConversations(
                "+12025550100",
                query,
                ConversationListing(query.rank(listOf(group))),
                now,
            )
        assertTrue(summary.startsWith(ConversationSummaries.NO_EXACT + " Conversations that also include others: 7 — "))
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
            ConversationSummaries.describeMessages(group, MessageListing(messages), now),
        )
    }

    @Test
    fun `an old thread is read back from its newest end`() {
        val total = ConversationSummaries.MAX_MESSAGES + 5
        val messages =
            (1..total).map { index ->
                ConversationMessage(true, alice, now - (total - index) * MINUTE, "message $index")
            }
        val summary = ConversationSummaries.describeMessages(group, MessageListing(messages), now)
        assertTrue(summary.contains("message $total"))
        assertTrue(!summary.contains("message 5 "))
        assertTrue(summary.contains(ConversationSummaries.olderMessages(ConversationSummaries.MAX_MESSAGES)))
    }

    @Test
    fun `a search that stopped at the scan cap does not claim the conversation is absent`() {
        val summary =
            ConversationSummaries.describeConversations(
                "\"climbing\"",
                ConversationQuery.of("climbing", emptyList(), usPhoneNumbers),
                ConversationListing(emptyList(), searched = 200),
                now,
            )
        assertEquals(
            "No text conversation matches \"climbing\". Only the 200 most recent conversations were searched; " +
                "an older conversation may exist. " + ConversationSummaries.STARTS_ONE,
            summary,
        )
    }

    @Test
    fun `a listing cut at the limit says more conversations exist`() {
        val query = ConversationQuery.of(null, emptyList(), usPhoneNumbers)
        val summary = ConversationSummaries.describeConversations("", query, ConversationListing(listOf(group), more = true), now)
        assertTrue(summary.endsWith(ConversationSummaries.MORE_CONVERSATIONS))
        val complete = ConversationSummaries.describeConversations("", query, ConversationListing(listOf(group)), now)
        assertTrue(!complete.contains(ConversationSummaries.MORE_CONVERSATIONS))
    }

    @Test
    fun `a short read of a long thread says earlier messages exist and how to see more`() {
        val messages = listOf(ConversationMessage(true, alice, now - MINUTE, "latest"))
        val summary = ConversationSummaries.describeMessages(group, MessageListing(messages, older = true), now)
        assertTrue(summary.contains("Earlier messages in this conversation are not shown; a limit up to 25 shows more."))
    }

    @Test
    fun `clipped message bodies are disclosed once per result`() {
        val long = "word ".repeat(ConversationSummaries.MAX_BODY)
        val messages =
            listOf(
                ConversationMessage(true, alice, now - 2 * MINUTE, long),
                ConversationMessage(true, alice, now - MINUTE, long),
                ConversationMessage(false, null, now, "short"),
            )
        val summary = ConversationSummaries.describeMessages(group, MessageListing(messages), now)
        assertEquals(1, Regex(Regex.escape(ConversationSummaries.CUT)).findAll(summary).count())
        val plain = ConversationSummaries.describeMessages(group, MessageListing(messages.takeLast(1)), now)
        assertTrue(!plain.contains(ConversationSummaries.CUT))
    }

    @Test
    fun `a message the store cut short is disclosed even when it fits the body limit`() {
        val messages = listOf(ConversationMessage(true, alice, now, "first part…", cut = true))
        val summary = ConversationSummaries.describeMessages(group, MessageListing(messages), now)
        assertTrue(summary.contains(ConversationSummaries.CUT))
    }

    private companion object {
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
