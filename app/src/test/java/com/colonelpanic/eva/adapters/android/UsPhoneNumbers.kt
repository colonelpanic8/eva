package com.colonelpanic.eva.adapters.android

/** Stands in for the platform's E.164 formatting with a US SIM. */
val usPhoneNumbers =
    PhoneNumberKey { number ->
        val digits = number.filter(Char::isDigit)
        if (number.trimStart().startsWith("+")) "+$digits" else "+1${digits.removePrefix("1")}"
    }
