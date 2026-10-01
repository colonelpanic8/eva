package com.colonelpanic.eva.data

import android.app.Application
import com.colonelpanic.eva.capability.ActionInitiator
import com.colonelpanic.eva.capability.InitiatorKind
import com.colonelpanic.eva.capability.InvocationRecord
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.conversation.ThreadItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class JournalDatabaseMigrationTest {
    @Test
    fun `version seven preserves legacy origins and round trips new initiation identities`() =
        runBlocking<Unit> {
            val context = RuntimeEnvironment.getApplication()
            val name = "initiator-${UUID.randomUUID()}.db"
            context.openOrCreateDatabase(name, 0, null).use { db ->
                db.execSQL(
                    "CREATE TABLE invocations (call_id TEXT PRIMARY KEY NOT NULL, fingerprint TEXT NOT NULL, request TEXT NOT NULL, destination TEXT, status TEXT NOT NULL, message TEXT NOT NULL, created_at INTEGER NOT NULL, capability_id TEXT NOT NULL, catalog_revision TEXT NOT NULL, title TEXT, thread_id TEXT, turn_id TEXT, arguments_json TEXT, provenance_json TEXT, data_json TEXT)",
                )
                db.execSQL(
                    "CREATE TABLE threads (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE turns (id TEXT PRIMARY KEY NOT NULL, thread_id TEXT NOT NULL, request TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, side_effect_call_id TEXT)",
                )
                db.execSQL(
                    "CREATE TABLE items (id TEXT PRIMARY KEY NOT NULL, thread_id TEXT NOT NULL, turn_id TEXT, created_at INTEGER NOT NULL, type TEXT NOT NULL, text TEXT, spoken INTEGER, truncated INTEGER, call_id TEXT, capability_id TEXT, title TEXT, arguments TEXT, notice_kind TEXT, leg_id TEXT, instructions TEXT, history_items INTEGER)",
                )
                db.execSQL("INSERT INTO threads VALUES ('thread','Old',1,1)")
                db.execSQL(
                    "INSERT INTO items (id,thread_id,created_at,type,call_id,capability_id,title,arguments) VALUES ('old','thread',1,'ACTION_CALL','old','eva.test','Old action','{}')",
                )
                db.execSQL(
                    "INSERT INTO invocations (call_id,fingerprint,request,status,message,created_at,capability_id,catalog_revision) VALUES ('old','fingerprint','Old request','COMPLETED','Done',1,'eva.test','revision')",
                )
                db.version = 7
            }

            val initiator = ActionInitiator(InitiatorKind.TEXT_AGENT, inputId = "input", responseId = "response", legId = "text-leg")
            val journal = JournalDatabase(context, name)
            try {
                val repository = SqliteInvocationRepository(journal)
                val store = SqliteConversationStore(journal)
                val old = repository.history().single()
                assertNull(old.initiator)
                val oldItem = store.items("thread").single() as ThreadItem.ActionCall
                assertNull(oldItem.initiator)
                repository.claim(old.copy(callId = "new", initiator = initiator))
                store.append(oldItem.copy(id = "new", callId = "new", createdAtMillis = 2, initiator = initiator))
            } finally {
                journal.close()
            }
            val reopened = JournalDatabase(context, name)
            try {
                val receipt = SqliteInvocationRepository(reopened).byCallIds(listOf("new")).getValue("new")
                val items = SqliteConversationStore(reopened).items("thread")
                assertEquals(initiator, receipt.initiator)
                assertEquals(initiator, (items.last() as ThreadItem.ActionCall).initiator)
                val entries =
                    com.colonelpanic.eva.conversation
                        .projectEntries(emptyList(), items, mapOf("new" to receipt))
                assertEquals(initiator, entries.last().initiator)
            } finally {
                reopened.close()
                context.deleteDatabase(name)
            }
        }

    @Test
    fun `version two migrates receipts and supports conversation items`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val name = "migration-${UUID.randomUUID()}.db"
            var helper: JournalDatabase? = null
            try {
                context.openOrCreateDatabase(name, 0, null).use { db ->
                    db.execSQL(
                        "CREATE TABLE invocations (call_id TEXT PRIMARY KEY NOT NULL, fingerprint TEXT NOT NULL, " +
                            "request TEXT NOT NULL, destination TEXT, status TEXT NOT NULL, message TEXT NOT NULL, " +
                            "created_at INTEGER NOT NULL, capability_id TEXT NOT NULL, catalog_revision INTEGER NOT NULL, title TEXT)",
                    )
                    db.execSQL(
                        "INSERT INTO invocations VALUES " +
                            "('old','fingerprint','map Park','Park','HANDED_OFF','Opened',1,'eva.maps.search',2,'Search maps')",
                    )
                    db.version = 2
                }

                helper = JournalDatabase(context, name)
                val repository = SqliteInvocationRepository(helper)
                val store = SqliteConversationStore(helper)
                val old = repository.history().single()
                assertEquals("old", old.callId)
                assertEquals("Search maps", old.title)
                assertNull(old.threadId)
                assertNull(old.turnId)

                val thread = store.createThread("Migrated")
                val item =
                    ThreadItem.ActionCall(
                        "item",
                        thread.id,
                        null,
                        2,
                        "new",
                        "eva.test",
                        "New action",
                        mapOf("value" to "quoted \"text\""),
                    )
                store.append(item)
                assertEquals(listOf(item), store.items(thread.id))

                val linked =
                    InvocationRecord(
                        "new",
                        "new-fingerprint",
                        "new request",
                        null,
                        InvocationStatus.COMPLETED,
                        "Done",
                        2,
                        "eva.test",
                        "3",
                        "New action",
                        threadId = thread.id,
                        turnId = "turn",
                    )
                repository.claim(linked)
                assertEquals(mapOf("new" to linked), repository.byCallIds(listOf("missing", "new")))
            } finally {
                helper?.close()
                context.deleteDatabase(name)
            }
        }

    @Test
    fun `version six keeps its items and gains text legs`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val name = "migration-${UUID.randomUUID()}.db"
            var helper: JournalDatabase? = null
            try {
                context.openOrCreateDatabase(name, 0, null).use { db ->
                    db.execSQL(
                        "CREATE TABLE invocations (call_id TEXT PRIMARY KEY NOT NULL, fingerprint TEXT NOT NULL, " +
                            "request TEXT NOT NULL, destination TEXT, status TEXT NOT NULL, message TEXT NOT NULL, " +
                            "created_at INTEGER NOT NULL, capability_id TEXT NOT NULL, catalog_revision TEXT NOT NULL, " +
                            "title TEXT, thread_id TEXT, turn_id TEXT, arguments_json TEXT, provenance_json TEXT, data_json TEXT)",
                    )
                    db.execSQL(
                        "CREATE TABLE threads (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE turns (id TEXT PRIMARY KEY NOT NULL, thread_id TEXT NOT NULL, request TEXT NOT NULL, " +
                            "status TEXT NOT NULL, created_at INTEGER NOT NULL, side_effect_call_id TEXT)",
                    )
                    db.execSQL(
                        "CREATE TABLE items (id TEXT PRIMARY KEY NOT NULL, thread_id TEXT NOT NULL, turn_id TEXT, " +
                            "created_at INTEGER NOT NULL, type TEXT NOT NULL, text TEXT, spoken INTEGER, truncated INTEGER, " +
                            "call_id TEXT, capability_id TEXT, title TEXT, arguments TEXT, notice_kind TEXT)",
                    )
                    db.execSQL("INSERT INTO threads VALUES ('thread','Old',1,1)")
                    db.execSQL(
                        "INSERT INTO items (id, thread_id, turn_id, created_at, type, call_id, capability_id, title, arguments) " +
                            "VALUES ('old','thread','turn',1,'ACTION_CALL','call-1','eva.test','Old action','{}')",
                    )
                    db.version = 6
                }

                helper = JournalDatabase(context, name)
                val store = SqliteConversationStore(helper)
                val old = ThreadItem.ActionCall("old", "thread", "turn", 1, "call-1", "eva.test", "Old action", emptyMap())
                val leg = ThreadItem.TextLeg("leg", "thread", "turn", 2, "Finish in text", "Instructions", 4)
                val legAction = old.copy(id = "new", createdAtMillis = 3, callId = "call-2", legId = "leg")
                store.append(leg)
                store.append(legAction)
                assertEquals(listOf(old, leg, legAction), store.items("thread"))
            } finally {
                helper?.close()
                context.deleteDatabase(name)
            }
        }
}
