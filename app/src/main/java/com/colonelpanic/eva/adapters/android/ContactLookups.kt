package com.colonelpanic.eva.adapters.android

import android.provider.ContactsContract.CommonDataKinds.StructuredName

/** Which part of a stored name a lookup matches. The model picks this; nothing here guesses. */
enum class ContactField(
    val argument: String,
) {
    DISPLAY("name"),
    GIVEN("given"),
    FAMILY("family"),
    ;

    val column: String?
        get() =
            when (this) {
                DISPLAY -> null
                GIVEN -> StructuredName.GIVEN_NAME
                FAMILY -> StructuredName.FAMILY_NAME
            }

    companion object {
        val arguments = entries.map { it.argument }

        fun of(argument: String?) = entries.firstOrNull { it.argument == argument } ?: DISPLAY
    }
}

/** Selection fragments for the contacts queries, kept apart from the resolver so they can be tested. */
object ContactLookups {
    const val MAX_CONTACT_IDS = 50

    /** A bounded `IN` clause: SQLite takes a fixed argument list, and a huge name match must not build one unbounded. */
    fun idSelection(
        column: String,
        ids: Collection<Long>,
    ): Pair<String, Array<String>> {
        val bounded = ids.take(MAX_CONTACT_IDS)
        return "$column IN (${bounded.joinToString(",") { "?" }})" to bounded.map(Long::toString).toTypedArray()
    }
}
