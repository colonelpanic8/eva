package com.colonelpanic.eva.adapters.android

data class ConversationParticipant(
    val number: String,
    val name: String? = null,
) {
    val label: String get() = name?.takeIf(String::isNotBlank) ?: number

    /** Named participants also show their number, so a listing can be checked against a contacts result. */
    val listed: String get() = name?.takeIf(String::isNotBlank)?.let { "$it ($number)" } ?: number
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
    /** The phone's store gave more text than EVA read; [body] already ends with "…". */
    val cut: Boolean = false,
)

/** [more] when further matches exist; [searched] when only that many of the newest threads were scanned. */
data class ConversationListing(
    val conversations: List<Conversation>,
    val more: Boolean = false,
    val searched: Int? = null,
)

/** [older] when the conversation has messages before the first one listed. */
data class MessageListing(
    val messages: List<ConversationMessage>,
    val older: Boolean = false,
)

/**
 * What a conversation search asks for: name fragments, each of which must match a participant, and
 * phone numbers, each of which must be a participant. A conversation holding nobody else is exact.
 */
class ConversationQuery(
    val names: List<String>,
    numbers: List<String>,
    private val key: PhoneNumberKey,
) {
    private val keys = numbers.map(key::of).filter(String::isNotEmpty).distinct()

    val isEmpty: Boolean get() = names.isEmpty() && keys.isEmpty()

    fun includesNumbers(numbers: List<String>): Boolean {
        val present = numbers.map(key::of).toSet()
        return keys.all(present::contains)
    }

    fun includes(conversation: Conversation): Boolean =
        names.all { name -> conversation.participants.any { it.matchesName(name) } } &&
            includesNumbers(conversation.participants.map(ConversationParticipant::number))

    fun isExact(conversation: Conversation): Boolean =
        includes(conversation) &&
            conversation.participants.isNotEmpty() &&
            conversation.participants.all { participant ->
                key.of(participant.number) in keys || names.any { participant.matchesName(it) }
            }

    /** Exact conversations first, then those with the fewest other people, newest first within each. */
    fun rank(conversations: List<Conversation>): List<Conversation> =
        conversations
            .filter(::includes)
            .sortedWith(compareBy<Conversation>({ !isExact(it) }, { it.participants.size }).thenByDescending { it.lastMessageMillis })

    private fun ConversationParticipant.matchesName(fragment: String): Boolean {
        val digits = fragment.filter(Char::isDigit)
        return name?.lowercase()?.contains(fragment) == true ||
            (digits.length >= MIN_NUMBER_MATCH && number.filter(Char::isDigit).contains(digits))
    }

    companion object {
        const val MIN_NUMBER_MATCH = 4

        /** Commas separate people, so "Sarah, Mike" finds the chats that include both. */
        fun of(
            query: String?,
            numbers: List<String>,
            key: PhoneNumberKey,
        ) = ConversationQuery(
            query
                ?.split(',')
                ?.map { it.trim().lowercase() }
                ?.filter(String::isNotEmpty)
                .orEmpty(),
            numbers,
            key,
        )
    }
}

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
    const val STARTS_ONE =
        "Sending to their numbers starts one, or continues it if it exists; " +
            "a chat the messaging app carries over RCS is not visible here."
    const val NO_EXACT = "No text conversation has only those people. $STARTS_ONE"

    /**
     * The messaging app keeps RCS in its own store, so a chat that has moved to RCS reads back only its
     * older text-message side. Saying so stops a stale thread being relayed as if it were the whole story.
     */
    const val PARTIAL = "This is only the text-message side of the conversation; anything sent over RCS is not visible here."
    const val NO_MESSAGES =
        "That conversation has no messages EVA can read. A chat carried over RCS looks empty here, " +
            "because the messaging app keeps those messages rather than the phone's text message store."

    const val MORE_CONVERSATIONS = "More conversations match than are listed; name a person or number to narrow the search."
    const val CUT = "Long messages are cut short and end with \"…\"."

    fun searchedOnly(threads: Int) = "Only the $threads most recent conversations were searched; an older conversation may exist."

    fun olderMessages(shown: Int) =
        "Earlier messages in this conversation are not shown" +
            (if (shown < MAX_MESSAGES) "; a limit up to $MAX_MESSAGES shows more." else ".")

    fun describeConversations(
        asked: String,
        query: ConversationQuery,
        listing: ConversationListing,
        now: Long,
    ): String {
        val shown = listing.conversations.take(MAX_CONVERSATIONS)
        val more = listing.more || listing.conversations.size > shown.size
        val limits =
            listOfNotNull(
                MORE_CONVERSATIONS.takeIf { more },
                listing.searched?.let(::searchedOnly),
                CUT.takeIf { shown.any { isCut(it.snippet.orEmpty()) } },
            ).joinToString("") { " $it" }
        if (query.isEmpty) {
            if (shown.isEmpty()) return "No text conversations are on this phone."
            return "Recent conversations: ${lines(shown, now)}. $USE_THE_ID$limits"
        }
        if (shown.isEmpty()) return "No text conversation matches $asked.$limits $STARTS_ONE"
        val (exact, wider) = shown.partition(query::isExact)
        return buildString {
            if (exact.isEmpty()) {
                append(NO_EXACT)
            } else {
                append("Conversations with only $asked: ${lines(exact, now)}.")
            }
            if (wider.isNotEmpty()) append(" Conversations that also include others: ${lines(wider, now)}.")
            append(" ")
            append(USE_THE_ID)
            append(limits)
        }
    }

    private fun lines(
        conversations: List<Conversation>,
        now: Long,
    ) = conversations.joinToString("; ") { line(it, now) }

    fun describeMessages(
        conversation: Conversation,
        listing: MessageListing,
        now: Long,
    ): String {
        if (listing.messages.isEmpty()) return NO_MESSAGES
        val shown = listing.messages.takeLast(MAX_MESSAGES)
        return buildString {
            append("Conversation ${conversation.id} with ${participants(conversation)}, oldest message first: ")
            append(
                shown.joinToString(" | ") { message ->
                    "[${ago(message.sentMillis, now)}] ${speaker(message)}: ${body(message.body)}"
                },
            )
            append(". ")
            if (listing.older || listing.messages.size > shown.size) append(olderMessages(shown.size)).append(" ")
            if (shown.any { it.cut || isCut(it.body) }) append(CUT).append(" ")
            append(PARTIAL)
        }
    }

    /** Whoever is not the user needs a name; the user's own side is only ever "You". */
    private fun speaker(message: ConversationMessage): String = if (message.incoming) message.sender?.label ?: "Unknown number" else "You"

    private fun flatten(value: String) = value.replace(Regex("\\s+"), " ").trim()

    private fun isCut(value: String) = flatten(value).length > MAX_BODY

    private fun body(value: String): String {
        val flattened = flatten(value)
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
            append("${conversation.id} — ")
            append(conversation.participants.joinToString(", ") { it.listed }.ifEmpty { "an unknown number" })
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
