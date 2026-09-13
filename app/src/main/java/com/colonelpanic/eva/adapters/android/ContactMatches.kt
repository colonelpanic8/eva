package com.colonelpanic.eva.adapters.android

data class ContactPhone(
    val number: String,
    val kind: String,
)

data class ContactMatch(
    val name: String,
    val phones: List<ContactPhone>,
)

/**
 * Orders lookup results by how well they match what was asked for and renders them as one line,
 * so the model can act on the likeliest person instead of asking about every near miss.
 */
object ContactMatches {
    const val MAX_CONTACTS = 5
    const val MAX_PHONES = 3
    const val PICK_BEST =
        "Text or call the best match when it plainly fits what the user said; ask only if another match fits just as well."
    const val AMBIGUOUS = "Several contacts match equally well; ask which one before acting."

    private val kindOrder = listOf("mobile", "work mobile", "main", "home", "work")

    fun describe(
        query: String,
        matches: List<ContactMatch>,
    ): String {
        val ranked = rank(query, matches)
        if (ranked.isEmpty()) return "No contact with a phone number matches \"$query\"."
        val shown = ranked.take(MAX_CONTACTS)
        val best = shown.first()
        val others = shown.drop(1)
        val tied = others.any { score(query, it.name) == score(query, best.name) }
        return buildString {
            append("Best match for \"$query\": ${line(best)}.")
            if (others.isNotEmpty()) append(" Other matches: ${others.joinToString("; ", transform = ::line)}.")
            if (ranked.size > shown.size) append(" ${ranked.size - shown.size} further matches were not listed.")
            append(" ")
            append(if (tied) AMBIGUOUS else PICK_BEST)
        }
    }

    /** Exact and word-leading matches come first; each contact's most callable number leads its line. */
    fun rank(
        query: String,
        matches: List<ContactMatch>,
    ): List<ContactMatch> =
        merge(matches)
            .sortedWith(compareByDescending<ContactMatch> { score(query, it.name) }.thenBy { it.name.lowercase() })
            .map { match -> match.copy(phones = match.phones.sortedBy { phoneRank(it.kind) }) }

    /** One person often has an entry per account, and listing them twice only invites a needless question. */
    private fun merge(matches: List<ContactMatch>): List<ContactMatch> {
        val byName = linkedMapOf<String, Pair<String, LinkedHashMap<String, ContactPhone>>>()
        for (match in matches) {
            val entry = byName.getOrPut(match.name.lowercase()) { match.name to linkedMapOf() }
            match.phones.forEach { phone -> entry.second.getOrPut(phone.number.filter(Char::isDigit)) { phone } }
        }
        return byName.values.map { (name, phones) -> ContactMatch(name, phones.values.toList()) }
    }

    private fun score(
        query: String,
        name: String,
    ): Int {
        val needle = query.trim().lowercase()
        val haystack = name.lowercase()
        val words = haystack.split(' ', '-', '.').filter(String::isNotBlank)
        return when {
            haystack == needle -> 5
            words.any { it == needle } -> 4
            haystack.startsWith(needle) -> 3
            words.any { it.startsWith(needle) } -> 2
            haystack.contains(needle) -> 1
            else -> 0
        }
    }

    private fun phoneRank(kind: String) = kindOrder.indexOf(kind).takeIf { it >= 0 } ?: kindOrder.size

    private fun line(match: ContactMatch) =
        match.name + ": " + match.phones.take(MAX_PHONES).joinToString(", ") { "${it.kind} ${it.number}" }
}
