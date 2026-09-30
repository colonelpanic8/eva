package com.colonelpanic.eva.adapters.android

/** Who a message is addressed to: explicit numbers, or the members of a conversation already on the phone. */
object MessageRecipients {
    const val MAX_RECIPIENTS = 10
    const val SEPARATOR = ","
    const val INVALID = "Enter one phone number, or several separated by commas, or a conversationId from the conversation search."

    val phone = Regex("\\+?[0-9][0-9 ()-]{2,24}")

    /** Null when any entry is not a plain phone number, so nothing typed into a number is passed on to an app. */
    fun parse(value: String): List<String>? {
        val entries = value.split(SEPARATOR).map(String::trim).filter(String::isNotEmpty)
        if (entries.isEmpty() || entries.size > MAX_RECIPIENTS) return null
        if (entries.any { !valid(it) }) return null
        return entries.distinctBy { it.filter(Char::isDigit) }
    }

    fun valid(number: String) = phone.matches(number) && number.count { it in '0'..'9' } in 3..15

    /** Digits and a leading plus only: the MMS and SMS layers both want a bare address. */
    fun normalize(number: String) = (if (number.trimStart().startsWith("+")) "+" else "") + number.filter(Char::isDigit)
}
