package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.adapters.android.MessageRecipients
import java.net.URI

/** Closed, versioned names; declarations never supply executable validation expressions. */
object NamedValidators {
    val names: Set<String> = setOf("phoneNumber", "httpUrl", "emailAddress")

    fun accepts(
        name: String,
        value: String,
    ): Boolean =
        when (name) {
            "phoneNumber" -> {
                MessageRecipients.phone.matches(value)
            }

            "httpUrl" -> {
                runCatching {
                    val uri = URI(value)
                    uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null &&
                        (uri.port == -1 || uri.port in 1..65535)
                }.getOrDefault(false)
            }

            "emailAddress" -> {
                value.length in 3..320 && value.count { it == '@' } == 1 &&
                    !value.startsWith('@') && !value.endsWith('@') && value.none { it.isWhitespace() || it.isISOControl() || it in ",;<>" }
            }

            else -> {
                false
            }
        }
}
