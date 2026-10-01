package com.colonelpanic.eva.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class MemoryNote(
    val name: String,
    val text: String,
    val updatedAtMillis: Long,
    /** The conversation EVA learned this in, for a note it saved on its own. */
    val threadId: String? = null,
)

/** Notes the user kept, and notes EVA learned on its own that wait in an inbox for review. */
data class Memories(
    val kept: List<MemoryNote> = emptyList(),
    val inbox: List<MemoryNote> = emptyList(),
)

/** Whole-file storage for memory notes; each write replaces its file atomically. */
interface MemoryFiles {
    /** Null when the file has never been written. */
    fun read(name: String): String?

    fun write(
        name: String,
        text: String,
    )
}

/** Personal runtime data, deliberately separate from portable configuration. */
class MemoryStore(
    private val files: MemoryFiles,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val keptFile = "memories.json"
    private val inboxFile = "memory-inbox.json"
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(Memories())

    /** Empty until the first read or write; [load] fills it for the memory screen. */
    val state = mutableState.asStateFlow()

    suspend fun load(): Memories = withContext(Dispatchers.IO) { mutex.withLock { read() } }

    suspend fun all(): List<MemoryNote> = load().kept

    /** Saving a name the inbox also holds settles that note, since the user said what it is. */
    suspend fun save(
        name: String,
        text: String,
    ): MemoryNote =
        withContext(Dispatchers.IO) {
            requireNote(name, text)
            mutex.withLock {
                val memories = read()
                require(memories.kept.size < MAX_KEPT || memories.kept.any { it.name == name }) {
                    "Memory is full. Forget a note before adding another."
                }
                val note = MemoryNote(name, text, clock())
                write(memories.copy(kept = memories.kept.replacing(note), inbox = memories.inbox.filterNot { it.name == name }))
                note
            }
        }

    /** A full inbox drops its oldest unreviewed note rather than refusing what EVA just learned. */
    suspend fun learn(
        name: String,
        text: String,
        threadId: String?,
    ): MemoryNote =
        withContext(Dispatchers.IO) {
            requireNote(name, text)
            mutex.withLock {
                val memories = read()
                require(memories.kept.none { it.name == name }) {
                    "The user already kept a note with this name. Learned notes never replace kept ones."
                }
                val note = MemoryNote(name, text, clock(), threadId)
                val inbox =
                    (memories.inbox.filterNot { it.name == name } + note)
                        .sortedByDescending { it.updatedAtMillis }
                        .take(MAX_INBOX)
                        .sortedBy { it.name }
                write(memories.copy(inbox = inbox))
                note
            }
        }

    /** Moves a learned note out of the inbox as the user's own; false when it is no longer there. */
    suspend fun keep(name: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val memories = read()
                val note = memories.inbox.firstOrNull { it.name == name } ?: return@withLock false
                require(memories.kept.size < MAX_KEPT || memories.kept.any { it.name == name }) {
                    "Memory is full. Forget a note before keeping another."
                }
                write(Memories(memories.kept.replacing(note), memories.inbox - note))
                true
            }
        }

    /** Removes the named note wherever it is, kept or waiting for review. */
    suspend fun forget(name: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val memories = read()
                val remaining = Memories(memories.kept.filterNot { it.name == name }, memories.inbox.filterNot { it.name == name })
                if (remaining == memories) return@withLock false
                write(remaining)
                true
            }
        }

    private fun requireNote(
        name: String,
        text: String,
    ) {
        require(name.isNotBlank() && name.length <= 120 && name == name.trim())
        require(text.isNotBlank() && text.length <= 1000)
    }

    private fun List<MemoryNote>.replacing(note: MemoryNote) = (filterNot { it.name == note.name } + note).sortedBy { it.name }

    private fun read(): Memories = Memories(read(keptFile), read(inboxFile)).also { mutableState.value = it }

    private fun read(file: String): List<MemoryNote> = files.read(file)?.let { Json.decodeFromString<List<MemoryNote>>(it) } ?: emptyList()

    private fun write(memories: Memories) {
        val current = mutableState.value
        if (memories.kept != current.kept) write(keptFile, memories.kept)
        if (memories.inbox != current.inbox) write(inboxFile, memories.inbox)
        mutableState.value = memories
    }

    private fun write(
        file: String,
        notes: List<MemoryNote>,
    ) = files.write(file, Json.encodeToString(notes))

    companion object {
        const val MAX_KEPT = 200
        const val MAX_INBOX = 50
    }
}
