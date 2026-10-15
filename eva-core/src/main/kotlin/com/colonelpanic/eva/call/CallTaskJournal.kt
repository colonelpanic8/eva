package com.colonelpanic.eva.call

import com.colonelpanic.eva.conversation.ConversationStore
import com.colonelpanic.eva.conversation.NoticeKind
import com.colonelpanic.eva.conversation.ThreadItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.UUID

interface CallTaskJournal {
    suspend fun save(snapshot: CallTaskSnapshot)

    suspend fun latest(): List<CallTaskSnapshot>
}

/**
 * One CALL_TASK notice: the task with only the transcript items after [transcriptFrom]. The
 * transcript is append-only, so a record with [transcriptFrom] 0 is complete on its own.
 */
@Serializable
data class CallTaskRecord(
    val transcriptFrom: Int,
    val task: CallTaskSnapshot,
)

/** A task folded from its records; [transcriptMissing] counts earlier items whose records were not read. */
data class FoldedCallTask(
    val task: CallTaskSnapshot,
    val transcriptMissing: Int = 0,
)

/** Folds CALL_TASK notice texts, oldest first, into each task's latest state. */
class CallTaskFold {
    private val tasks = linkedMapOf<String, FoldedCallTask>()

    val values: Collection<FoldedCallTask> get() = tasks.values

    operator fun get(id: String): FoldedCallTask? = tasks[id]

    /** Returns the task the record belongs to, after applying it. */
    fun add(text: String): FoldedCallTask {
        val record = ThreadCallTaskJournal.decode(text)
        val previous = tasks[record.task.id]
        val folded =
            when {
                record.transcriptFrom == 0 -> {
                    FoldedCallTask(record.task)
                }

                previous == null -> {
                    FoldedCallTask(record.task, record.transcriptFrom)
                }

                else -> {
                    val known = previous.transcriptMissing + previous.task.transcript.size
                    val kept = previous.task.transcript.take((record.transcriptFrom - previous.transcriptMissing).coerceAtLeast(0))
                    FoldedCallTask(
                        record.task.copy(transcript = kept + record.task.transcript),
                        previous.transcriptMissing + (record.transcriptFrom - known).coerceAtLeast(0),
                    )
                }
            }
        tasks[record.task.id] = folded
        return folded
    }
}

/** Structured call records use the existing append-only thread journal; no separate persistence authority. */
class ThreadCallTaskJournal(
    private val store: ConversationStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : CallTaskJournal {
    private class Written(
        val task: CallTaskSnapshot,
        val sinceComplete: Int,
    )

    private val written = java.util.concurrent.ConcurrentHashMap<String, Written>()

    /**
     * Appends only transcript items the thread does not already hold. A complete record is written
     * first in each process, whenever the task state changes, and every [CHECKPOINT] records, so a
     * bounded read of recent items still finds a whole transcript nearby.
     */
    override suspend fun save(snapshot: CallTaskSnapshot) {
        val previous = written[snapshot.id]
        if (previous?.task == snapshot) return
        val complete =
            previous == null ||
                previous.task.state != snapshot.state ||
                previous.sinceComplete + 1 >= CHECKPOINT ||
                previous.task.transcript.size > snapshot.transcript.size ||
                snapshot.transcript.take(previous.task.transcript.size) != previous.task.transcript
        val from = if (complete) 0 else previous.task.transcript.size
        store.append(
            ThreadItem.Notice(
                UUID.randomUUID().toString(),
                snapshot.threadId,
                snapshot.turnId,
                nowMillis(),
                NoticeKind.CALL_TASK,
                Json.encodeToString(
                    CallTaskRecord.serializer(),
                    CallTaskRecord(from, snapshot.copy(transcript = snapshot.transcript.drop(from))),
                ),
            ),
        )
        written[snapshot.id] = Written(snapshot, if (complete) 0 else previous.sinceComplete + 1)
    }

    override suspend fun latest(): List<CallTaskSnapshot> =
        store.threads().flatMap { thread ->
            val fold = CallTaskFold()
            store
                .items(thread.id, Int.MAX_VALUE)
                .filterIsInstance<ThreadItem.Notice>()
                .filter { it.kind == NoticeKind.CALL_TASK }
                .forEach { fold.add(it.text) }
            fold.values.map { it.task }
        }

    companion object {
        private const val CHECKPOINT = 32

        /** Records written before incremental journaling hold a complete snapshot at the top level. */
        fun decode(text: String): CallTaskRecord {
            val json = Json.parseToJsonElement(text).jsonObject
            return if ("task" in json) {
                Json.decodeFromJsonElement(CallTaskRecord.serializer(), json)
            } else {
                CallTaskRecord(0, Json.decodeFromJsonElement(CallTaskSnapshot.serializer(), json))
            }
        }
    }
}
