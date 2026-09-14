package com.colonelpanic.eva.data

import android.app.Application
import com.colonelpanic.eva.capability.InvocationStatus
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
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class JournalMigrationTest {
    @Test
    fun `integer revisions migrate preserving fingerprints titles order and recovery`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            for (version in 1..3) {
                val name = "migration-${UUID.randomUUID()}.db"
                try {
                    context.openOrCreateDatabase(name, 0, null).use { db ->
                        val revisionType = if (version == 3) "TEXT" else "INTEGER"
                        val title = if (version >= 2) ", title TEXT" else ""
                        db.execSQL(
                            "CREATE TABLE invocations (call_id TEXT PRIMARY KEY NOT NULL, fingerprint TEXT NOT NULL, request TEXT NOT NULL, destination TEXT, status TEXT NOT NULL, message TEXT NOT NULL, created_at INTEGER NOT NULL, capability_id TEXT NOT NULL, catalog_revision $revisionType NOT NULL$title)",
                        )
                        val oldTitle = if (version >= 2) ",'Original title'" else ""
                        db.execSQL(
                            "INSERT INTO invocations VALUES ('first','original-fingerprint','request','Park','HANDED_OFF','Opened',1,'eva.maps',9$oldTitle)",
                        )
                        db.execSQL(
                            "INSERT INTO invocations VALUES ('second','uncertain-fingerprint','request',null,'DISPATCHING','Sending',2,'eva.maps',9$oldTitle)",
                        )
                        db.version = version
                    }
                    SqliteInvocationRepository(context, name).use { journal ->
                        val records = journal.history()
                        assertEquals(listOf("first", "second"), records.map { it.callId })
                        assertEquals(listOf("9", "9"), records.map { it.catalogRevision })
                        assertEquals("original-fingerprint", records.first().fingerprint)
                        assertNull(records.first().arguments)
                        assertNull(records.first().provenance)
                        if (version == 1) assertNull(records.first().title) else assertEquals("Original title", records.first().title)
                        journal.recoverInterrupted()
                        assertEquals(InvocationStatus.UNKNOWN, journal.history().last().status)
                        journal.claim(records.first().copy(callId = "new", catalogRevision = "sha256:abc"))
                    }
                    SqliteInvocationRepository(context, name).use { journal ->
                        assertEquals("sha256:abc", journal.history().last().catalogRevision)
                    }
                    context.openOrCreateDatabase(name, 0, null).use { db ->
                        db.rawQuery("SELECT typeof(catalog_revision) FROM invocations", null).use { cursor ->
                            while (cursor.moveToNext()) assertEquals("text", cursor.getString(0))
                        }
                    }
                } finally {
                    context.deleteDatabase(name)
                }
            }
        }
}
