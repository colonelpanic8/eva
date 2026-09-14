package com.colonelpanic.eva.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationRecord
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SqliteInvocationRepositoryTest {
    @Test
    fun versionOneJournalMigratesWithoutLosingReceipts() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "migration-${UUID.randomUUID()}.db"
            try {
                context.openOrCreateDatabase(name, 0, null).use { db ->
                    db.execSQL(
                        "CREATE TABLE invocations (call_id TEXT PRIMARY KEY NOT NULL, fingerprint TEXT NOT NULL, " +
                            "request TEXT NOT NULL, destination TEXT, status TEXT NOT NULL, message TEXT NOT NULL, " +
                            "created_at INTEGER NOT NULL, capability_id TEXT NOT NULL, catalog_revision INTEGER NOT NULL)",
                    )
                    db.execSQL("INSERT INTO invocations VALUES ('old','fp','map Park','Park','HANDED_OFF','Opened',1,'eva.maps.search',2)")
                    db.version = 1
                }
                SqliteInvocationRepository(context, name).use { repository ->
                    val old = repository.history().single()
                    assertEquals("old", old.callId)
                    assertNull(old.title)
                    assertNull(old.threadId)
                    assertNull(old.turnId)
                    repository.claim(old.copy(callId = "new", title = "Search maps", threadId = "thread", turnId = "turn"))
                }
                SqliteInvocationRepository(context, name).use { repository ->
                    assertEquals(listOf(null, "Search maps"), repository.history().map { it.title })
                    assertEquals(listOf(null, "thread"), repository.history().map { it.threadId })
                    assertEquals(listOf(null, "turn"), repository.history().map { it.turnId })
                }
            } finally {
                context.deleteDatabase(name)
            }
        }

    @Test
    fun interruptedDispatchSurvivesReopenWithoutReplay() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "test-${UUID.randomUUID()}.db"
            val proposal =
                ToolProposal("session:'call", CapabilityRegistry.MAP_SEARCH, mapOf("destination" to "Park & café"), "map Park & café")
            val record =
                InvocationRecord(
                    proposal.callId,
                    proposal.fingerprint(),
                    proposal.request,
                    "Park & café",
                    InvocationStatus.CLAIMED,
                    "Pending",
                    1L,
                    proposal.capabilityId,
                    proposal.catalogRevision,
                )
            try {
                SqliteInvocationRepository(context, name).use { repository ->
                    repository.claim(record)
                    repository.transition(record.callId, InvocationStatus.CLAIMED, InvocationStatus.DISPATCHING, "Sending")
                }
                SqliteInvocationRepository(context, name).use { repository ->
                    repository.recoverInterrupted()
                    val restored = repository.history().single()
                    assertEquals(InvocationStatus.UNKNOWN, restored.status)
                    assertEquals(record.destination, restored.destination)
                    assertFalse(repository.claim(record).isNew)
                    val backend =
                        object : ExecutionBackend {
                            override suspend fun unavailableReason(): String? = null

                            override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome =
                                error("Uncertain calls must not execute again")
                        }
                    assertEquals(
                        restored,
                        CapabilityDispatcher(
                            CapabilityRegistry(mapOf(CapabilityRegistry.MAP_SEARCH to backend)),
                            repository,
                        ).execute(proposal),
                    )
                }
            } finally {
                context.deleteDatabase(name)
            }
        }

    @Test
    fun recoveryDistinguishesUnsentAndAlreadyHandedOff() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "test-${UUID.randomUUID()}.db"
            try {
                SqliteInvocationRepository(context, name).use { repository ->
                    repository.claim(
                        InvocationRecord(
                            "unsent",
                            "a",
                            "map Park",
                            "Park",
                            InvocationStatus.CLAIMED,
                            "Pending",
                            1,
                            CapabilityRegistry.MAP_SEARCH,
                            CapabilityRegistry.REVISION,
                        ),
                    )
                    repository.claim(
                        InvocationRecord(
                            "sent",
                            "b",
                            "map Ferry Building",
                            "Ferry Building",
                            InvocationStatus.HANDED_OFF,
                            "Opened",
                            0,
                            CapabilityRegistry.MAP_SEARCH,
                            CapabilityRegistry.REVISION,
                        ),
                    )
                }
                SqliteInvocationRepository(context, name).use { repository ->
                    repository.recoverInterrupted()
                    assertEquals(listOf(InvocationStatus.NOT_EXECUTED, InvocationStatus.HANDED_OFF), repository.history().map { it.status })
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
}
