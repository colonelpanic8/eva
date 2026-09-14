package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus

/**
 * When the user last dealt with each phone number: the last direct text thread on the phone, and
 * the last time EVA texted or dialed it. Contacts search uses it to break ties between people with
 * the same name and to lead each contact with the number the user actually uses.
 */
class ContactHistory(
    private val messaged: Map<String, Long> = emptyMap(),
    private val chosen: Map<String, Long> = emptyMap(),
) {
    fun lastUsed(number: String): Long? {
        val key = key(number)
        return listOfNotNull(messaged[key], chosen[key]).maxOrNull()
    }

    fun lastUsed(match: ContactMatch): Long? = match.phones.mapNotNull { lastUsed(it.number) }.maxOrNull()

    companion object {
        val NONE = ContactHistory()

        /** Android's own loose number comparison: the trailing digits survive every country-code and trunk-prefix variation. */
        const val MATCH_DIGITS = 7

        fun key(number: String) = number.filter(Char::isDigit).takeLast(MATCH_DIGITS)
    }
}

/**
 * Remembers the numbers an action reached once it went through, so the next contacts search offers
 * that number first. A failed or unconfirmed send is not a choice the user made.
 */
class RemembersNumbers(
    private val delegate: ExecutionBackend,
    private val argument: String,
    private val record: suspend (List<String>) -> Unit,
) : ExecutionBackend {
    override suspend fun unavailableReason(): String? = delegate.unavailableReason()

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
        val outcome = delegate.execute(arguments)
        if (outcome.status == InvocationStatus.COMPLETED || outcome.status == InvocationStatus.HANDED_OFF) {
            arguments[argument]?.let(MessageRecipients::parse)?.let { record(it) }
        }
        return outcome
    }
}
