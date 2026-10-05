package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.conversation.prompt.Wording
import com.colonelpanic.eva.providers.HistoryItem
import com.colonelpanic.eva.providers.openai.toOpenAiMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionProjectionTest {
    private val turn = Turn("task", "thread", "Research", TurnStatus.OPEN, 0)
    private val question = QuestionEvidence("q", "task", "leg", QuestionSource.TEXT_AGENT, "Which city?")
    private val pending = ThreadItem.Question("ask", "thread", "task", 2, question)
    private val answered =
        pending.copy(
            id = "answer",
            createdAtMillis = 3,
            evidence =
                question.copy(
                    resolution = QuestionResolution.ACCEPTED,
                    answer = "Tokyo",
                    provenance = AnswerProvenance.VOICE_MODEL,
                    transcriptItemId = "speech",
                ),
        )

    @Test fun `an exchange is nested inside its leg without becoming the final answer`() {
        val items =
            listOf(
                ThreadItem.UserMessage("request", "thread", "task", 0, "Research", true),
                ThreadItem.TextLeg("leg", "thread", "task", 1, "Research", "instructions", 1),
                pending,
                answered,
                ThreadItem.AssistantMessage("final", "thread", "task", 4, "Done", false),
            )
        val entries = projectEntries(listOf(turn), items, emptyMap())
        assertEquals("Done", entries.single { it.id == "task" }.response)
        val exchange = entries.single { it.id == "q" }
        assertEquals("leg", exchange.parentId)
        assertEquals(answered.evidence, exchange.question)
        assertEquals(
            1,
            groups(entries)
                .single()
                .children
                .single()
                .children.size,
        )
    }

    @Test fun `followed history framing reaches the provider separately from exchange data`() {
        val wording = Wording(messages = mapOf(Wording.BACKGROUND_QUESTION_HISTORY to "Followed question framing"))
        val history =
            projectHistory(listOf(answered), emptyMap(), questionHistoryNote = wording.message(Wording.BACKGROUND_QUESTION_HISTORY))
        val messages = history.single().toOpenAiMessages()
        assertEquals("developer", messages.first().role)
        assertEquals("Followed question framing", messages.first().text)
        assertEquals("user", messages.last().role)
        assertTrue(messages.last().text.contains("Tokyo"))
    }

    @Test fun `a tail containing only the answer still includes the question and attribution as data`() {
        val history = projectHistory(listOf(pending, answered), emptyMap(), limit = 1)
        val data = (history.last() as HistoryItem.Question).data
        assertTrue(data.toString().contains("Which city?"))
        assertTrue(data.toString().contains("Tokyo"))
        assertTrue(data.toString().contains("VOICE_MODEL"))
        assertTrue(data.toString().contains("speech"))
        assertEquals(1, history.filterIsInstance<HistoryItem.Question>().size)
    }
}
