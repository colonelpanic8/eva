package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.capability.ActionInitiator

/**
 * A durable conversation. Sessions attach to a thread and detach again; the thread and
 * the work its turns are doing outlive them. See docs/architecture.md.
 */
data class Thread(
    val id: String,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

enum class TurnStatus { OPEN, ANSWERED, INTERRUPTED, FAILED }

/** One accepted user request and everything done to answer it. */
data class Turn(
    val id: String,
    val threadId: String,
    val request: String,
    val status: TurnStatus,
    val createdAtMillis: Long,
)

enum class NoticeKind { SESSION_STARTED, SESSION_ENDED, REHOMED, INTERRUPTED, COVERAGE_LIMIT, CALL_TASK }

/** An attributed item in a thread, in the order it happened. */
sealed interface ThreadItem {
    val id: String
    val threadId: String
    val turnId: String?
    val createdAtMillis: Long

    data class Question(
        override val id: String,
        override val threadId: String,
        override val turnId: String,
        override val createdAtMillis: Long,
        val evidence: QuestionEvidence,
    ) : ThreadItem

    data class UserMessage(
        override val id: String,
        override val threadId: String,
        override val turnId: String?,
        override val createdAtMillis: Long,
        val text: String,
        val spoken: Boolean,
    ) : ThreadItem

    data class AssistantMessage(
        override val id: String,
        override val threadId: String,
        override val turnId: String?,
        override val createdAtMillis: Long,
        val text: String,
        val spoken: Boolean,
        val truncated: Boolean = false,
    ) : ThreadItem

    /** The outcome lives in the invocation journal under [callId] and is joined at read time, never copied. */
    data class ActionCall(
        override val id: String,
        override val threadId: String,
        override val turnId: String?,
        override val createdAtMillis: Long,
        val callId: String,
        val capabilityId: String,
        val title: String,
        val arguments: Map<String, String>,
        /** The [TextLeg] that proposed this call; null for the turn's own connection. */
        val legId: String? = null,
        val initiator: ActionInitiator? = null,
    ) : ThreadItem

    /**
     * A turn continued on a background text leg: delegated by voice with [task], or re-homed
     * after its connection ended when [task] is null. [instructions] are exactly what the leg
     * was opened with; it was also seeded with [historyItems] items of thread history. [prompt] is
     * the part of [instructions] written for this leg alone, after the stock instructions; null
     * when the leg had none or was recorded before it was kept apart.
     */
    data class TextLeg(
        override val id: String,
        override val threadId: String,
        override val turnId: String?,
        override val createdAtMillis: Long,
        val task: String?,
        val instructions: String,
        val historyItems: Int,
        val prompt: String? = null,
    ) : ThreadItem

    data class Notice(
        override val id: String,
        override val threadId: String,
        override val turnId: String?,
        override val createdAtMillis: Long,
        val kind: NoticeKind,
        val text: String,
    ) : ThreadItem
}
