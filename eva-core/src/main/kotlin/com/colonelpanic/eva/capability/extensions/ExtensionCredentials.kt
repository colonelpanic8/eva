package com.colonelpanic.eva.capability.extensions

/** Credentials the user provisioned for installed extensions, held on this device only. */
fun interface ExtensionCredentialSource {
    /** Values for the [declared] names that have one, bound to [identity]'s user, package and signer. */
    fun values(
        identity: ExtensionIdentity,
        declared: List<CredentialSpec>,
    ): CredentialValues

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
