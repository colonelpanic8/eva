package com.colonelpanic.eva.conversation

import kotlinx.coroutines.flow.Flow

/**
 * Durable threads, turns, and items. Shares the journal database with the invocation
 * repository so a claim, its turn linkage, and the turn's side-effect reservation commit
 * together. Action outcomes are not stored here; readers join them from the journal.
 */
interface ConversationStore {
    /** Emits the id of a thread whose items or turns changed. */
    val changes: Flow<String>

    suspend fun createThread(title: String): Thread

    /** Newest updated first. */
    suspend fun threads(): List<Thread>

    suspend fun thread(id: String): Thread?

    suspend fun retitle(
        id: String,
        title: String,
    )

    /** Oldest first, the last [limit] items. */
    suspend fun items(
        threadId: String,
        limit: Int = DEFAULT_ITEM_LIMIT,
    ): List<ThreadItem>

    suspend fun turns(threadId: String): List<Turn>

    suspend fun openTurn(
        threadId: String,
        request: String,
        id: String,
    ): Turn

    suspend fun closeTurn(
        turnId: String,
        status: TurnStatus,
    )

    /** True if [callId] now holds the turn's single side-effect claim, or already did. */
    suspend fun reserveSideEffect(
        turnId: String,
        callId: String,
    ): Boolean

    suspend fun append(item: ThreadItem)

    /** OPEN turns left by a dead process become INTERRUPTED; called once at application start. */
    suspend fun recoverInterrupted(): List<Turn>

    companion object {
        const val DEFAULT_ITEM_LIMIT = 200
    }
}
