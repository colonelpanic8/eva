package com.colonelpanic.eva.conversation

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

enum class NoticeKind { SESSION_STARTED, SESSION_ENDED, REHOMED, INTERRUPTED }

/** An attributed item in a thread, in the order it happened. */
sealed interface ThreadItem {
    val id: String
    val threadId: String
    val turnId: String?
    val createdAtMillis: Long

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
