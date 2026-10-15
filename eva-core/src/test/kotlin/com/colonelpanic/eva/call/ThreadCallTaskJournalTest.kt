package com.colonelpanic.eva.call

import com.colonelpanic.eva.conversation.MemoryConversationStore
import com.colonelpanic.eva.conversation.NoticeKind
import com.colonelpanic.eva.conversation.ThreadItem
import com.colonelpanic.eva.conversation.Turn
import com.colonelpanic.eva.conversation.TurnStatus
import com.colonelpanic.eva.conversation.projectEntries
import com.colonelpanic.eva.conversation.projectHistory
import com.colonelpanic.eva.providers.HistoryItem
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadCallTaskJournalTest {
    private val traits = CallProviderTraits(CallMediaKind.LOCAL_PCM, true, true, true, true)

    private fun task(threadId: String) =
        CallTaskSnapshot("task", threadId, "turn", "cellular", traits, CallBrief("+13034997111", "Get the time", "", "", emptySet()))

    private fun item(n: Int) = CallTranscriptItem("r$n", if (n % 2 == 0) CallSpeaker.REMOTE else CallSpeaker.AGENT, "line $n", n.toLong())

    private suspend fun MemoryConversationStore.records(threadId: String) =
        items(threadId, Int.MAX_VALUE).filterIsInstance<ThreadItem.Notice>().filter { it.kind == NoticeKind.CALL_TASK }

    @Test fun recordsCarryOnlyNewTranscriptAndFoldBackToTheLatestTask() =
        runTest {
            val store = MemoryConversationStore()
            val thread = store.createThread("Call").id
            val journal = ThreadCallTaskJournal(store)
            var current = task(thread)
            journal.save(current)
            current = current.copy(state = CallTaskState.InCall)
            journal.save(current)
            for (n in 1..5) {
                current = current.copy(transcript = current.transcript + item(n))
                journal.save(current)
            }
            journal.save(current)
            current = current.copy(floor = CallFloor.OWNER)
            journal.save(current)

            val records = store.records(thread).map { ThreadCallTaskJournal.decode(it.text) }
            assertEquals(8, records.size)
            assertTrue(records.drop(2).all { it.task.transcript.size <= 1 })
            assertEquals(listOf(0, 1, 2, 3, 4, 5), records.drop(2).map { it.transcriptFrom })

            // A restarted process folds every record; its first save is complete again.
            assertEquals(listOf(current), ThreadCallTaskJournal(store).latest())
            ThreadCallTaskJournal(store).save(current.copy(floor = CallFloor.AGENT))
            assertEquals(
                5,
                ThreadCallTaskJournal
                    .decode(store.records(thread).last().text)
                    .task.transcript.size,
            )
        }

    @Test fun theStartActionShowsOneCardAndHistoryTellsTheCallOnce() =
        runTest {
            val store = MemoryConversationStore()
            val thread = store.createThread("Call").id
            store.append(ThreadItem.ActionCall("a", thread, "turn", 1, "task", CallCapabilities.START, "Delegate a phone call", emptyMap()))
            val journal = ThreadCallTaskJournal(store)
            var current = task(thread).copy(state = CallTaskState.InCall)
            for (n in 1..3) {
                current = current.copy(transcript = current.transcript + item(n))
                journal.save(current)
            }
            val items = store.items(thread, Int.MAX_VALUE)
            val entries = projectEntries(listOf(Turn("turn", thread, "Call NIST", TurnStatus.OPEN, 0)), items, emptyMap())
            assertEquals(current, entries.single { it.id == "task" }.callTask)
            assertEquals(2, entries.size)

            val evidence = projectHistory(items, emptyMap()).filterIsInstance<HistoryItem.ActionEvidence>()
            assertEquals(listOf("Delegate a phone call", "Phone call task"), evidence.map { it.title })
            val told =
                Json.decodeFromJsonElement(
                    CallTaskSnapshot.serializer(),
                    evidence
                        .last()
                        .data!!
                        .getValue("callTask")
                        .jsonObject,
                )
            assertEquals(current, told)
        }

    @Test fun recordsWrittenAsWholeSnapshotsStillFold() =
        runTest {
            val store = MemoryConversationStore()
            val thread = store.createThread("Call").id
            val legacy = task(thread).copy(state = CallTaskState.InCall, transcript = listOf(item(1)))
            store.append(
                ThreadItem.Notice(
                    "old",
                    thread,
                    "turn",
                    1,
                    NoticeKind.CALL_TASK,
                    Json.encodeToString(CallTaskSnapshot.serializer(), legacy),
                ),
            )
            val journal = ThreadCallTaskJournal(store)
            journal.save(legacy.copy(transcript = legacy.transcript + item(2)))
            journal.save(legacy.copy(transcript = legacy.transcript + item(2) + item(3)))
            assertEquals(
                listOf("r1", "r2", "r3"),
                journal
                    .latest()
                    .single()
                    .transcript
                    .map { it.id },
            )
        }

    @Test fun aBoundedHistoryWindowSaysHowMuchTranscriptItCouldNotRead() =
        runTest {
            val store = MemoryConversationStore()
            val thread = store.createThread("Call").id
            val journal = ThreadCallTaskJournal(store)
            var current = task(thread).copy(state = CallTaskState.InCall)
            for (n in 1..4) {
                current = current.copy(transcript = current.transcript + item(n))
                journal.save(current)
            }
            val window = store.items(thread, Int.MAX_VALUE).takeLast(2)
            val data = projectHistory(window, emptyMap(), readBounded = true).filterIsInstance<HistoryItem.ActionEvidence>().single().data!!
            assertEquals("2", data.getValue("earlierTranscriptItemsNotShown").toString())
            assertTrue(data.toString().contains("line 4"))
        }
}
