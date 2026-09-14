package com.colonelpanic.eva.conversation

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class MemoryConversationStore : ConversationStore {
    override val changes =
        MutableSharedFlow<String>(
            extraBufferCapacity = CHANGE_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private val mutex = Mutex()
    private val threadRecords = linkedMapOf<String, Thread>()
    private val turnRecords = linkedMapOf<String, Turn>()
    private val itemRecords = linkedMapOf<String, ThreadItem>()

    override suspend fun createThread(title: String): Thread {
        val created =
            mutex.withLock {
                val now = maxOf(System.currentTimeMillis(), (threadRecords.values.maxOfOrNull(Thread::updatedAtMillis) ?: 0) + 1)
                Thread(UUID.randomUUID().toString(), title, now, now).also { threadRecords[it.id] = it }
            }
        changes.tryEmit(created.id)
        return created
    }

    override suspend fun threads() = mutex.withLock { threadRecords.values.sortedByDescending(Thread::updatedAtMillis) }

    override suspend fun thread(id: String) = mutex.withLock { threadRecords[id] }

    override suspend fun retitle(
        id: String,
        title: String,
    ) {
        mutex.withLock {
            val current = checkNotNull(threadRecords[id]) { "Unknown thread: $id" }
            threadRecords[id] = current.copy(title = title, updatedAtMillis = nextTimestamp(current))
        }
        changes.tryEmit(id)
    }

    override suspend fun items(
        threadId: String,
        limit: Int,
    ): List<ThreadItem> {
        require(limit >= 0)
        return mutex.withLock { itemRecords.values.filter { it.threadId == threadId }.takeLast(limit) }
    }

    override suspend fun turns(threadId: String) = mutex.withLock { turnRecords.values.filter { it.threadId == threadId } }

    override suspend fun openTurn(
        threadId: String,
        request: String,
        id: String,
    ): Turn {
        val opened =
            mutex.withLock {
                check(threadId in threadRecords) { "Unknown thread: $threadId" }
                check(id !in turnRecords) { "Duplicate turn: $id" }
                Turn(id, threadId, request, TurnStatus.OPEN, System.currentTimeMillis()).also {
                    turnRecords[id] = it
                    bump(threadId)
                }
            }
        changes.tryEmit(threadId)
        return opened
    }

    override suspend fun closeTurn(
        turnId: String,
        status: TurnStatus,
    ) {
        val threadId =
            mutex.withLock {
                val current = checkNotNull(turnRecords[turnId]) { "Unknown turn: $turnId" }
                turnRecords[turnId] = current.copy(status = status)
                bump(current.threadId)
                current.threadId
            }
        changes.tryEmit(threadId)
    }

    override suspend fun reserveSideEffect(
        turnId: String,
        callId: String,
    ): Boolean {
        val result =
            mutex.withLock {
                val current = checkNotNull(turnRecords[turnId]) { "Unknown turn: $turnId" }
                when (current.sideEffectCallId) {
                    null -> {
                        turnRecords[turnId] = current.copy(sideEffectCallId = callId)
                        ReservationResult(true, current.threadId, true)
                    }

                    callId -> {
                        ReservationResult(true, current.threadId, false)
                    }

                    else -> {
                        ReservationResult(false, current.threadId, false)
                    }
                }
            }
        if (result.changed) changes.tryEmit(result.threadId)
        return result.reserved
    }

    override suspend fun append(item: ThreadItem) {
        mutex.withLock {
            check(item.threadId in threadRecords) { "Unknown thread: ${item.threadId}" }
            check(item.id !in itemRecords) { "Duplicate item: ${item.id}" }
            itemRecords[item.id] = item
            bump(item.threadId)
        }
        changes.tryEmit(item.threadId)
    }

    override suspend fun recoverInterrupted(): List<Turn> {
        val recovered =
            mutex.withLock {
                turnRecords.values
                    .filter { it.status == TurnStatus.OPEN }
                    .map { turn ->
                        turn.copy(status = TurnStatus.INTERRUPTED).also { turnRecords[turn.id] = it }
                    }
            }
        recovered.map { it.threadId }.distinct().forEach(changes::tryEmit)
        return recovered
    }

    private fun bump(threadId: String) {
        val current = checkNotNull(threadRecords[threadId])
        threadRecords[threadId] = current.copy(updatedAtMillis = nextTimestamp(current))
    }

    private fun nextTimestamp(thread: Thread) =
        maxOf(
            System.currentTimeMillis(),
            thread.updatedAtMillis + 1,
            threadRecords.values.maxOf(Thread::updatedAtMillis) + 1,
        )

    private data class ReservationResult(
        val reserved: Boolean,
        val threadId: String,
        val changed: Boolean,
    )

    companion object {
        private const val CHANGE_BUFFER_CAPACITY = 64
    }
}
