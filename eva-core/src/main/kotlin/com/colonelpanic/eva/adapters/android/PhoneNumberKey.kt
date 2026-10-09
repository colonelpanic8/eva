package com.colonelpanic.eva.adapters.android

/** One comparable form of a phone number, so the same line written two ways compares equal. */
fun interface PhoneNumberKey {
    fun of(number: String): String

    companion object {
        val E164 = Regex("\\+[1-9][0-9]{6,14}")
    }
}
