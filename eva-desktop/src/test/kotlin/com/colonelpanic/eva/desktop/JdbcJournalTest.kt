package com.colonelpanic.eva.desktop

import com.colonelpanic.eva.capability.ActionInitiator
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InitiatorKind
import com.colonelpanic.eva.capability.InvocationRecord
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.TestCapabilities
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.conversation.NoticeKind
import com.colonelpanic.eva.conversation.ThreadItem
import com.colonelpanic.eva.conversation.TurnStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.DriverManager

class JdbcJournalTest {
    @get:Rule val folder = TemporaryFolder()

    private val file get() = folder.root.resolve("eva-actions.db")

    @Test
    fun `version seven preserves legacy origins and round trips new initiation identities`() =
        runTest {
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { db ->
                db.createStatement().use { statement ->
                    statement.execute(
                        "CREATE TABLE invocations (call_id TEXT PRIMARY KEY NOT NULL, fingerprint TEXT NOT NULL, request TEXT NOT NULL, destination TEXT, status TEXT NOT NULL, message TEXT NOT NULL, created_at INTEGER NOT NULL, capability_id TEXT NOT NULL, catalog_revision TEXT NOT NULL, title TEXT, thread_id TEXT, turn_id TEXT, arguments_json TEXT, provenance_json TEXT, data_json TEXT)",
                    )
                    statement.execute(
                        "CREATE TABLE threads (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
                    )
                    statement.execute(
                        "CREATE TABLE turns (id TEXT PRIMARY KEY NOT NULL, thread_id TEXT NOT NULL, request TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, side_effect_call_id TEXT)",
                    )
                    statement.execute(
                        "CREATE TABLE items (id TEXT PRIMARY KEY NOT NULL, thread_id TEXT NOT NULL, turn_id TEXT, created_at INTEGER NOT NULL, type TEXT NOT NULL, text TEXT, spoken INTEGER, truncated INTEGER, call_id TEXT, capability_id TEXT, title TEXT, arguments TEXT, notice_kind TEXT, leg_id TEXT, instructions TEXT, history_items INTEGER)",
                    )
                    statement.execute("INSERT INTO threads VALUES ('thread','Old',1,1)")
                    statement.execute(
                        "INSERT INTO items (id,thread_id,created_at,type,call_id,capability_id,title,arguments) VALUES ('old','thread',1,'ACTION_CALL','old','eva.test','Old action','{}')",
                    )
                    statement.execute(
                        "INSERT INTO invocations (call_id,fingerprint,request,status,message,created_at,capability_id,catalog_revision) VALUES ('old','fingerprint','Old request','COMPLETED','Done',1,'eva.test','revision')",
                    )
                    statement.execute("PRAGMA user_version = 7")
                }
            }

            val initiator = ActionInitiator(InitiatorKind.TEXT_AGENT, inputId = "input", responseId = "response", legId = "text-leg")
            JdbcJournal(file).use { journal ->
                val repository = JdbcInvocationRepository(journal)
                val store = JdbcConversationStore(journal)
                val old = repository.history().single()
                assertNull(old.initiator)
                val oldItem = store.items("thread").single() as ThreadItem.ActionCall
                assertNull(oldItem.initiator)
                repository.claim(old.copy(callId = "new", initiator = initiator))
                store.append(oldItem.copy(id = "new", callId = "new", createdAtMillis = 2, initiator = initiator))
            }
            JdbcJournal(file).use { journal ->
                val receipt = JdbcInvocationRepository(journal).byCallIds(listOf("new")).getValue("new")
                val items = JdbcConversationStore(journal).items("thread")
                assertEquals(initiator, receipt.initiator)
                assertEquals(initiator, (items.last() as ThreadItem.ActionCall).initiator)
                val entries =
                    com.colonelpanic.eva.conversation
                        .projectEntries(emptyList(), items, mapOf("new" to receipt))
                assertEquals(initiator, entries.last().initiator)
            }
        }

    @Test
    fun `an executed action is not repeated after a restart`() =
        runTest {
            var runs = 0
            val backend =
                object : ExecutionBackend {
                    override suspend fun unavailableReason(): String? = null

                    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                        runs++
                        return ExecutionOutcome(InvocationStatus.COMPLETED, "Searched", buildJsonObject { put("hits", JsonPrimitive(2)) })
                    }
                }

            suspend fun dispatchOnce(): InvocationRecord =
                JdbcJournal(file).use { journal ->
                    val registry = TestCapabilities.registry(backend)
                    val dispatcher = CapabilityDispatcher(registry, JdbcInvocationRepository(journal))
                    dispatcher.execute(
                        ToolProposal(
                            "call-1",
                            TestCapabilities.SEARCH,
                            mapOf("destination" to "Park"),
                            "find the park",
                            registry.snapshot.revision,
                        ),
                    )
                }

            val first = dispatchOnce()
            val replay = dispatchOnce()

            assertEquals(1, runs)
            assertEquals(InvocationStatus.COMPLETED, replay.status)
            assertEquals(first.data, replay.data)
        }

    @Test
    fun `work left in flight by a dead process is recovered as uncertain or not sent`() =
        runTest {
            JdbcJournal(file).use { journal ->
                val repository = JdbcInvocationRepository(journal)
                repository.claim(record("sent", InvocationStatus.CLAIMED))
                repository.transition("sent", InvocationStatus.CLAIMED, InvocationStatus.DISPATCHING, "Sending")
                repository.claim(record("waiting", InvocationStatus.CLAIMED))
                val store = JdbcConversationStore(journal)
                val thread = store.createThread("Errands")
                store.openTurn(thread.id, "find the park", "turn-1")
            }

            JdbcJournal(file).use { journal ->
                val repository = JdbcInvocationRepository(journal)
                repository.recoverInterrupted()
                val records = repository.byCallIds(listOf("sent", "waiting"))
                assertEquals(InvocationStatus.UNKNOWN, records.getValue("sent").status)
                assertEquals(InvocationStatus.NOT_EXECUTED, records.getValue("waiting").status)
                val recovered = JdbcConversationStore(journal).recoverInterrupted()
                assertEquals(listOf(TurnStatus.INTERRUPTED), recovered.map { it.status })
            }
        }

    @Test
    fun `threads keep every item kind and their order`() =
        runTest {
            JdbcJournal(file).use { journal ->
                val store = JdbcConversationStore(journal)
                val thread = store.createThread("Errands")
                val items =
                    listOf(
                        ThreadItem.UserMessage("1", thread.id, "turn", 1, "find the park", false),
                        ThreadItem.ActionCall(
                            "2",
                            thread.id,
                            "turn",
                            2,
                            "call-1",
                            TestCapabilities.SEARCH,
                            "Search maps",
                            mapOf("destination" to "Park"),
                            null,
                        ),
                        ThreadItem.AssistantMessage("3", thread.id, "turn", 3, "Found it.", false, false),
                        ThreadItem.Notice("4", thread.id, "turn", 4, NoticeKind.INTERRUPTED, "Stopped."),
                    )
                items.forEach { store.append(it) }

                assertEquals(items, store.items(thread.id))
                assertTrue(store.threads().single().updatedAtMillis > thread.updatedAtMillis)
            }
        }

    private fun record(
        callId: String,
        status: InvocationStatus,
    ) = InvocationRecord(callId, "fingerprint", "request", null, status, "Claimed", 1, TestCapabilities.SEARCH, "revision")
}
