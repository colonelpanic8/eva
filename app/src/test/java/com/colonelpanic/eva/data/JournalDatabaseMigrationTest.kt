package com.colonelpanic.eva.data

import android.app.Application
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
}
