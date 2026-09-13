package com.colonelpanic.eva.adapters.android

data class ContactPhone(
    val number: String,
    val kind: String,
)

data class ContactMatch(
    val name: String,
    val phones: List<ContactPhone>,
)

/** Renders lookup results as one line the model can read back or act on. */
object ContactMatches {
    const val MAX_CONTACTS = 5
    const val MAX_PHONES = 3

    fun describe(
        query: String,
        matches: List<ContactMatch>,
    ): String {
        val shown = matches.take(MAX_CONTACTS)
        if (shown.isEmpty()) return "No contact with a phone number matches \"$query\"."
        val listing =
            shown.joinToString("; ") { match ->
                match.name + ": " + match.phones.take(MAX_PHONES).joinToString(", ") { "${it.kind} ${it.number}" }
            }
        val count = if (matches.size > shown.size) "first ${shown.size} of ${matches.size}" else "${shown.size}"
        val noun = if (matches.size == 1) "contact" else "contacts"
        return "Found $count $noun matching \"$query\": $listing."
    }
}
