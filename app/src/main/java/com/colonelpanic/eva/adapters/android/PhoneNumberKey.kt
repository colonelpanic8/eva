package com.colonelpanic.eva.adapters.android

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale

/** One comparable form of a phone number, so the same line written two ways compares equal. */
fun interface PhoneNumberKey {
    fun of(number: String): String

    companion object {
        val E164 = Regex("\\+[1-9][0-9]{6,14}")
    }
}

/**
 * E.164, reading a number without a country code as one from the SIM's country: "(202) 555-0100"
 * and "+1 202 555 0100" agree, while numbers that share only trailing digits do not. What is not a
 * dialable number, such as a short code, keeps its digits and leading plus and must match exactly.
 */
class PlatformPhoneNumberKey(
    context: Context,
) : PhoneNumberKey {
    private val country = homeCountry(context)

    override fun of(number: String): String =
        country?.let { PhoneNumberUtils.formatNumberToE164(number, it) } ?: MessageRecipients.normalize(number)

    private companion object {
        fun homeCountry(context: Context): String? {
            val telephony = context.getSystemService(TelephonyManager::class.java)
            return listOfNotNull(telephony?.simCountryIso, telephony?.networkCountryIso, Locale.getDefault().country)
                .firstOrNull(String::isNotBlank)
                ?.uppercase(Locale.ROOT)
        }
    }
}
