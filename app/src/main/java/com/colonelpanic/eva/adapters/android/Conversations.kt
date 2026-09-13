package com.colonelpanic.eva.adapters.android

data class ConversationParticipant(
    val number: String,
    val name: String? = null,
) {
    val label: String get() = name?.takeIf(String::isNotBlank) ?: number
}

data class Conversation(
    val id: Long,
    val participants: List<ConversationParticipant>,
    val lastMessageMillis: Long,
    val snippet: String? = null,
) {
    val isGroup: Boolean get() = participants.size > 1
}

data class ConversationMessage(
    val incoming: Boolean,
    val sender: ConversationParticipant?,
    val sentMillis: Long,
    val body: String,
)

/**
 * Renders threads and their recent messages as the lines the model reads. Conversation IDs are
 * included because they are how a reply reaches an existing thread: a group is addressed by its
 * whole participant set, which the model cannot reliably retype from a transcript.
 */
object ConversationSummaries {
    const val MAX_CONVERSATIONS = 10
    const val MAX_MESSAGES = 25
    const val MAX_BODY = 240
    const val USE_THE_ID = "Pass conversationId to the read, send, or draft actions to act on one of these."

    /**
     * The messaging app keeps RCS in its own store, so a chat that has moved to RCS reads back only its
     * older text-message side. Saying so stops a stale thread being relayed as if it were the whole story.
     */
    const val PARTIAL = "This is only the text-message side of the conversation; anything sent over RCS is not visible here."
    const val NO_MESSAGES =
        "That conversation has no messages EVA can read. A chat carried over RCS looks empty here, " +
            "because the messaging app keeps those messages rather than the phone's text message store."

    fun describeConversations(
        query: String?,
        conversations: List<Conversation>,
        now: Long,
    ): String {
        val asked = query?.trim().orEmpty()
        if (conversations.isEmpty()) {
            return if (asked.isEmpty()) "No text conversations are on this phone." else "No conversation matches \"$asked\"."
        }
        val shown = conversations.take(MAX_CONVERSATIONS)
        return buildString {
            append(if (asked.isEmpty()) "Recent conversations" else "Conversations matching \"$asked\"")
            append(": ")
            append(shown.joinToString("; ") { line(it, now) })
            append(". ")
            append(USE_THE_ID)
        }
    }

    fun describeMessages(
        conversation: Conversation,
        messages: List<ConversationMessage>,
        now: Long,
    ): String {
        if (messages.isEmpty()) return NO_MESSAGES
        val shown = messages.takeLast(MAX_MESSAGES)
        return buildString {
            append("Conversation ${conversation.id} with ${participants(conversation)}, oldest message first: ")
            append(
                shown.joinToString(" | ") { message ->
                    "[${ago(message.sentMillis, now)}] ${speaker(message)}: ${body(message.body)}"
                },
            )
            append(". ")
            append(PARTIAL)
        }
    }

    /** Whoever is not the user needs a name; the user's own side is only ever "You". */
    private fun speaker(message: ConversationMessage): String = if (message.incoming) message.sender?.label ?: "Unknown number" else "You"

    private fun body(value: String): String {
        val flattened = value.replace(Regex("\\s+"), " ").trim()
        return when {
            flattened.isEmpty() -> "(no text)"
            flattened.length <= MAX_BODY -> flattened
            else -> flattened.take(MAX_BODY - 1).trimEnd() + "…"
        }
    }

    private fun line(
        conversation: Conversation,
        now: Long,
    ): String =
        buildString {
            append("${conversation.id} — ${participants(conversation)}")
            if (conversation.isGroup) append(" (group of ${conversation.participants.size})")
            append(" — ${ago(conversation.lastMessageMillis, now)}")
            conversation.snippet?.takeIf(String::isNotBlank)?.let { append(" — \"${body(it)}\"") }
        }

    fun participants(conversation: Conversation): String =
        conversation.participants.takeIf(List<ConversationParticipant>::isNotEmpty)?.joinToString(", ") { it.label }
            ?: "an unknown number"

    /** Plain elapsed wording: the model reads this aloud, and an absolute timestamp rarely helps there. */
    fun ago(
        moment: Long,
        now: Long,
    ): String {
        val seconds = (now - moment) / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            seconds < 60 -> "just now"
            minutes < 60 -> count(minutes, "minute")
            hours < 24 -> count(hours, "hour")
            days < 30 -> count(days, "day")
            days < 365 -> count(days / 30, "month")
            else -> count(days / 365, "year")
        }
    }

    private fun count(
        value: Long,
        unit: String,
    ) = "$value $unit${if (value == 1L) "" else "s"} ago"
}
