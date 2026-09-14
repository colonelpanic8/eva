package com.colonelpanic.eva.adapters.android

data class ContactPhone(
    val number: String,
    val kind: String,
)

data class ContactMatch(
    val name: String,
    val phones: List<ContactPhone>,
    val givenName: String? = null,
    val familyName: String? = null,
    val nicknames: List<String> = emptyList(),
)

/**
 * Orders contacts by how well they fit a heard name and renders the result as one line, so the
 * model can act on the likeliest person instead of asking about every near miss. Names are matched
 * approximately because speech recognition misspells them; people the user texts break ties
 * between equal names, and each contact leads with the number the user last used for them.
 */
object ContactMatches {
    const val MAX_CONTACTS = 5
    const val MAX_PHONES = 3
    const val PICK_BEST =
        "Text or call the best match when it plainly fits what the user said; ask only if another match fits just as well. " +
            "Use a contact's first listed number unless the user asks for a different one, such as work or home."
    const val PICK_RECENT =
        "Several contacts share this name, but the best match is the only one the user has been in touch with, so use it " +
            "unless the user says otherwise. Use its first listed number unless the user asks for a different one."
    const val AMBIGUOUS = "Several contacts match equally well; ask which one before acting."
    const val PARTIAL =
        "No contact matches every part of that name. Unless one of these is plainly who the user meant, search again " +
            "with another spelling or ask the user; do not text or call a partial match unasked."

    /** Scores this close are the same fit; a spelling difference is not evidence for one person over another. */
    private const val TIE = 0.05

    /** Contacts this far behind the best are not worth reading out. */
    private const val SHOWN = 0.3

    /** A stored name the query covers entirely beats one it covers only in part: "Sarah" means Sarah before Sarah Chen. */
    private const val COMPLETE = 0.1

    private const val DAY_MILLIS = 86_400_000L

    private val kindOrder = listOf("mobile", "work mobile", "main", "home", "work")

    private class Scored(
        val match: ContactMatch,
        /** How well the query fits, 0 to 1. */
        val fit: Double,
        val rank: Double,
        val nickname: String?,
        val lastUsed: Long?,
    )

    fun describe(
        query: String,
        matches: List<ContactMatch>,
        field: ContactField = ContactField.DISPLAY,
        history: ContactHistory = ContactHistory.NONE,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        val ranked = score(query, matches, field, history)
        if (ranked.isEmpty()) return "No contact with a phone number matches \"$query\"."
        val best = ranked.first()
        val nearby = ranked.drop(1).filter { it.rank >= best.rank - SHOWN }
        val others = nearby.take(MAX_CONTACTS - 1)
        val tied = ranked.drop(1).filter { it.rank >= best.rank - TIE }
        return buildString {
            append("Best match for \"$query\": ${line(best, history, nowMillis)}.")
            if (others.isNotEmpty()) append(" Other matches: ${others.joinToString("; ") { line(it, history, nowMillis) }}.")
            if (nearby.size > others.size) append(" ${nearby.size - others.size} further matches were not listed.")
            append(" ")
            append(
                when {
                    best.fit < NameSimilarity.STRONG -> PARTIAL
                    tied.isEmpty() -> PICK_BEST
                    best.lastUsed != null && tied.none { it.lastUsed != null } -> PICK_RECENT
                    else -> AMBIGUOUS
                },
            )
        }
    }

    /** Best first; each contact's most usable number leads its phone list. */
    fun rank(
        query: String,
        matches: List<ContactMatch>,
        field: ContactField = ContactField.DISPLAY,
        history: ContactHistory = ContactHistory.NONE,
    ): List<ContactMatch> = score(query, matches, field, history).map { it.match }

    private fun score(
        query: String,
        matches: List<ContactMatch>,
        field: ContactField,
        history: ContactHistory,
    ): List<Scored> {
        val scored =
            merge(matches)
                .map { match -> scored(query, match, field, history) }
                .filter { it.fit > 0 }
                .sortedWith(compareByDescending<Scored> { it.rank }.thenBy { it.match.name.lowercase() })
        val top = scored.firstOrNull() ?: return emptyList()
        // Among equally good names, the person the user has been in touch with most recently leads.
        val (tied, rest) = scored.partition { it.rank >= top.rank - TIE }
        val byContact =
            tied.sortedWith(
                compareByDescending<Scored> { it.lastUsed ?: Long.MIN_VALUE }
                    .thenByDescending { it.rank }
                    .thenBy { it.match.name.lowercase() },
            )
        return byContact + rest
    }

    private fun scored(
        query: String,
        match: ContactMatch,
        field: ContactField,
        history: ContactHistory,
    ): Scored {
        val names =
            when (field) {
                ContactField.DISPLAY -> listOf(match.name to null) + match.nicknames.map { it to it }
                ContactField.GIVEN -> listOf((match.givenName ?: match.name) to null) + match.nicknames.map { it to it }
                ContactField.FAMILY -> listOf((match.familyName ?: match.name) to null)
            }
        val (name, nickname, fit) =
            names
                .map { (name, nickname) -> Triple(name, nickname, NameSimilarity.score(query, name)) }
                .maxBy { it.third }
        val rank = fit + if (fit > 0 && NameSimilarity.complete(query, name)) COMPLETE else 0.0
        val phones =
            match.phones.sortedWith(
                compareByDescending<ContactPhone> { history.lastUsed(it.number) ?: Long.MIN_VALUE }.thenBy { phoneRank(it.kind) },
            )
        return Scored(match.copy(phones = phones), fit, rank, nickname, history.lastUsed(match))
    }

    /** One person often has an entry per account, and listing them twice only invites a needless question. */
    private fun merge(matches: List<ContactMatch>): List<ContactMatch> {
        val byName = linkedMapOf<String, ContactMatch>()
        for (match in matches) {
            val key = match.name.lowercase()
            val previous = byName[key]
            byName[key] =
                if (previous == null) {
                    match.copy(phones = match.phones.distinctBy { it.number.filter(Char::isDigit) })
                } else {
                    previous.copy(
                        phones = (previous.phones + match.phones).distinctBy { it.number.filter(Char::isDigit) },
                        givenName = previous.givenName ?: match.givenName,
                        familyName = previous.familyName ?: match.familyName,
                        nicknames = (previous.nicknames + match.nicknames).distinct(),
                    )
                }
        }
        return byName.values.toList()
    }

    private fun phoneRank(kind: String) = kindOrder.indexOf(kind).takeIf { it >= 0 } ?: kindOrder.size

    private fun line(
        scored: Scored,
        history: ContactHistory,
        nowMillis: Long,
    ): String {
        val match = scored.match
        val notes =
            listOfNotNull(
                scored.nickname?.let { "nickname $it" },
                "approximate match".takeIf { scored.fit < 1.0 && scored.nickname == null },
                scored.lastUsed?.let { "last in touch ${ago(it, nowMillis)}" },
            )
        val phones =
            match.phones.take(MAX_PHONES).mapIndexed { index, phone ->
                val used = index == 0 && match.phones.size > 1 && history.lastUsed(phone.number) != null
                "${phone.kind} ${phone.number}" + if (used) " (last used)" else ""
            }
        return match.name + notes.joinToString("; ", " (", ")").takeIf { notes.isNotEmpty() }.orEmpty() +
            ": " + phones.joinToString(", ")
    }

    private fun ago(
        millis: Long,
        nowMillis: Long,
    ): String {
        val days = ((nowMillis - millis) / DAY_MILLIS).coerceAtLeast(0)
        return when {
            days == 0L -> "today"
            days == 1L -> "yesterday"
            days < 14 -> "$days days ago"
            days < 60 -> "${days / 7} weeks ago"
            days < 730 -> "${days / 30} months ago"
            else -> "${days / 365} years ago"
        }
    }
}
