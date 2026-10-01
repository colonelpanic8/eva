package com.colonelpanic.eva.conversation

import kotlinx.coroutines.flow.Flow

/**
 * Durable threads, turns, and items. Shares the journal database with the invocation
 * repository. Action outcomes are joined from the journal by their turn and call identities.
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

    suspend fun append(item: ThreadItem)

    /** OPEN turns left by a dead process become INTERRUPTED; called once at application start. */
    suspend fun recoverInterrupted(): List<Turn>

    companion object {
        const val DEFAULT_ITEM_LIMIT = 200
    }
}
