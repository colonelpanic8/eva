package com.colonelpanic.eva.capability.extensions

/** Credentials the user provisioned for installed extensions, held on this device only. */
fun interface ExtensionCredentialSource {
    /** Values for the [declared] names that have one, bound to [identity]'s user, package and signer. */
    fun values(
        identity: ExtensionIdentity,
        declared: List<CredentialSpec>,
    ): CredentialValues

    /**
     * [identity] replied that the [delivered] values (name to the value sent) were wrong, with
     * [message] explaining why. A value replaced since it was sent is not affected.
     */
    fun rejected(
        identity: ExtensionIdentity,
        delivered: Map<String, String>,
        message: String,
    ) = Unit

    companion object {
        val NONE = ExtensionCredentialSource { _, _ -> CredentialValues.Ready(emptyMap()) }
    }
}

sealed interface CredentialValues {
    class Ready(
        val values: Map<String, String>,
    ) : CredentialValues {
        override fun toString() = "Ready(${values.keys})"
    }

    /** Nothing may be sent, such as a value saved for another signer; [message] says what to do. */
    data class Refused(
        val message: String,
    ) : CredentialValues
}

/** What a provider said about one credential value. Each [message] is the provider's or EVA's explanation, possibly empty. */
sealed interface CredentialVerdict {
    val message: String

    data class Accepted(
        override val message: String,
    ) : CredentialVerdict

    data class Rejected(
        override val message: String,
    ) : CredentialVerdict

    /** Nothing was learned: the provider could not check, could not be reached, or replied malformed. */
    data class Unverified(
        override val message: String,
    ) : CredentialVerdict
}

/**
 * Asks [identity] whether [value] works for [spec], sending only that one credential. The check
 * is a provider-protocol request like `describe`, not a tool call: it is never journaled and the
 * model never sees it.
 */
suspend fun ExtensionConnectionManager.checkCredential(
    identity: ExtensionIdentity,
    descriptor: Descriptor,
    spec: CredentialSpec,
    value: String,
): CredentialVerdict {
    if (!spec.checkable) {
        return CredentialVerdict.Unverified("${descriptor.title} cannot check ${spec.title} ahead of time; EVA finds out on first use.")
    }
    val arguments =
        try {
            ExtensionProtocol.checkArguments(spec.name, value)
        } catch (_: IllegalArgumentException) {
            return CredentialVerdict.Unverified("${spec.title} is too long to send in one request, so EVA could not check it.")
        }
    return when (val exchange = check(identity, descriptor.revision, arguments)) {
        is ExtensionExchange.Reply -> {
            runCatching { ExtensionProtocol.checkResult(exchange.json) }.getOrElse {
                CredentialVerdict.Unverified("${descriptor.title} answered the check with a reply EVA could not read.")
            }
        }

        is ExtensionExchange.Unavailable -> {
            CredentialVerdict.Unverified(exchange.message)
        }

        ExtensionExchange.Uncertain -> {
            CredentialVerdict.Unverified("${descriptor.title} did not answer the check in time.")
        }

        is ExtensionExchange.Oversized -> {
            CredentialVerdict.Unverified("${descriptor.title} answered the check with a reply over ${exchange.limit} bytes.")
        }
    }
}
