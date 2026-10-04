package com.colonelpanic.eva.conversation

import android.app.Application
import com.colonelpanic.eva.data.JournalDatabase
import com.colonelpanic.eva.data.SqliteConversationStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

abstract class ConversationStoreContract {
    protected abstract fun fixture(): StoreFixture

    @Test
    fun `threads and every item type round trip in order with a tail limit`() =
        fixture().use { fixture ->
            runBlocking {
                val first = fixture.store.createThread("First")
                val second = fixture.store.createThread("Second")
                assertEquals(listOf(second.id, first.id), fixture.store.threads().map(Thread::id))
                val turn = fixture.store.openTurn(first.id, "Do the thing", "turn-1")
                val expected =
                    listOf(
                        ThreadItem.UserMessage("user", first.id, turn.id, 1, "Please do it", true),
                        ThreadItem.AssistantMessage("assistant", first.id, turn.id, 2, "Working", false, true),
                        ThreadItem.ActionCall(
                            "action",
                            first.id,
                            turn.id,
                            3,
                            "call-1",
                            "eva.test",
                            "Test action",
                            linkedMapOf("quote" to "a \"value\"", "line" to "one\ntwo"),
                        ),
                        ThreadItem.TextLeg("leg", first.id, turn.id, 4, "Finish in text", "Full\ninstructions", 3),
                        ThreadItem.TextLeg("rehomed", first.id, turn.id, 5, null, "Continue", 0),
                        ThreadItem.ActionCall("leg-action", first.id, turn.id, 6, "call-2", "eva.test", "Test action", emptyMap(), "leg"),
                        ThreadItem.Notice("notice", first.id, null, 7, NoticeKind.REHOMED, "Moved to background"),
                        ThreadItem.Question(
                            "question",
                            first.id,
                            turn.id,
                            8,
                            QuestionEvidence(
                                "q",
                                turn.id,
                                "leg",
                                QuestionSource.TEXT_AGENT,
                                "Which city?",
                                QuestionResolution.ACCEPTED,
                                "Tokyo",
                                AnswerProvenance.TYPED,
                            ),
                        ),
                    )
                expected.forEach { fixture.store.append(it) }

                assertEquals(listOf(first.id, second.id), fixture.store.threads().map(Thread::id))
                assertEquals(expected, fixture.store.items(first.id))
                assertEquals(expected.takeLast(2), fixture.store.items(first.id, 2))
                assertEquals(emptyList<ThreadItem>(), fixture.store.items(first.id, 0))
                assertEquals(turn, fixture.store.turns(first.id).single())
            }
        }

    @Test
    fun `session catalogs round trip per thread with a tail limit`() =
        fixture().use { fixture ->
            runBlocking {
                val thread = fixture.store.createThread("Catalogs")
                val other = fixture.store.createThread("Other")
                val records =
                    (1..3).map {
                        SessionCatalogRecord(
                            "s$it",
                            thread.id,
                            "turn".takeIf { _ -> it == 3 },
                            it.toLong(),
                            if (it == 3) SessionKind.TEXT_LEG else SessionKind.TEXT,
                            "leg".takeIf { _ -> it == 3 },
                            null,
                            "rev$it",
                            listOf(OfferedTool("eva.test", "Test $it")),
                            if (it == 2) listOf("extension.skipped") else emptyList(),
                        )
                    }
                records.forEach { fixture.store.recordSessionCatalog(it) }
                fixture.store.recordSessionCatalog(records[0].copy(id = "elsewhere", threadId = other.id))

                assertEquals(records, fixture.store.sessionCatalogs(thread.id))
                assertEquals(records.takeLast(2), fixture.store.sessionCatalogs(thread.id, 2))
                assertEquals(0, fixture.store.itemCount(thread.id))
            }
        }

    @Test
    fun `only the newest session catalogs per thread are retained`() =
        fixture().use { fixture ->
            runBlocking {
                val thread = fixture.store.createThread("Many sessions")
                val other = fixture.store.createThread("Other")

                fun record(
                    index: Int,
                    threadId: String,
                ) = SessionCatalogRecord(
                    "$threadId-$index",
                    threadId,
                    null,
                    index.toLong(),
                    SessionKind.VOICE,
                    null,
                    null,
                    "r",
                    emptyList(),
                    emptyList(),
                )
                fixture.store.recordSessionCatalog(record(0, other.id))
                val limit = ConversationStore.SESSION_CATALOG_LIMIT
                (1..limit + 5).forEach { fixture.store.recordSessionCatalog(record(it, thread.id)) }

                assertEquals((6..limit + 5).map { "${thread.id}-$it" }, fixture.store.sessionCatalogs(thread.id, limit * 2).map { it.id })
                assertEquals(listOf("${other.id}-0"), fixture.store.sessionCatalogs(other.id).map { it.id })
            }
        }

    @Test
    fun `recovery interrupts only open turns and only once`() =
        fixture().use { fixture ->
            runBlocking {
                val thread = fixture.store.createThread("Recovery")
                fixture.store.openTurn(thread.id, "Finished", "answered")
                fixture.store.closeTurn("answered", TurnStatus.ANSWERED)
                fixture.store.openTurn(thread.id, "Abandoned", "open")

                assertEquals(listOf("open"), fixture.store.recoverInterrupted().map(Turn::id))
                assertEquals(
                    listOf(TurnStatus.ANSWERED, TurnStatus.INTERRUPTED),
                    fixture.store.turns(thread.id).map(Turn::status),
                )
                assertEquals(emptyList<Turn>(), fixture.store.recoverInterrupted())
            }
        }

    @Test
    fun `mutations emit the changed thread id`() =
        fixture().use { fixture ->
            runTest {
                val emitted = mutableListOf<String>()
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        fixture.store.changes
                            .take(3)
                            .toList(emitted)
                    }

                val thread = fixture.store.createThread("Changes")
                fixture.store.openTurn(thread.id, "Request", "turn")
                fixture.store.append(ThreadItem.UserMessage("item", thread.id, "turn", 1, "Request", false))
                collector.join()

                assertEquals(listOf(thread.id, thread.id, thread.id), emitted)
            }
        }
}

class MemoryConversationStoreContractTest : ConversationStoreContract() {
    override fun fixture() = StoreFixture(MemoryConversationStore())
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class SqliteConversationStoreContractTest : ConversationStoreContract() {
    override fun fixture(): StoreFixture {
        val context = RuntimeEnvironment.getApplication()
        val name = "store-contract-${UUID.randomUUID()}.db"
        val helper = JournalDatabase(context, name)
        return StoreFixture(SqliteConversationStore(helper)) {
            helper.close()
            context.deleteDatabase(name)
        }
    }
}

class StoreFixture(
    val store: ConversationStore,
    private val closeAction: () -> Unit = {},
) : AutoCloseable {
    override fun close() = closeAction()
}
