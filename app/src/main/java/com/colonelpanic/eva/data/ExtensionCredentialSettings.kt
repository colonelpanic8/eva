package com.colonelpanic.eva.data

import android.annotation.SuppressLint
import android.content.Context
import com.colonelpanic.eva.capability.extensions.CredentialSpec
import com.colonelpanic.eva.capability.extensions.CredentialValues
import com.colonelpanic.eva.capability.extensions.ExtensionCredentialSource
import com.colonelpanic.eva.capability.extensions.ExtensionIdentity
import com.colonelpanic.eva.data.configuration.EvaConfigurationCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Values installed extensions declare in their descriptor's `credentials`, such as a key
 * passphrase. Each is saved in this phone's [SecretStore], beside the user and signer it was
 * entered for, and is delivered only to that same identity. The portable configuration carries
 * only `extension/<package>/<name>` references.
 */
class ExtensionCredentialSettings(
    context: Context,
    private val onCredentialChanged: (String) -> Unit = {},
    private val secrets: SecretStore = SecretStore(context),
) : ExtensionCredentialSource {
    private val prefs = context.getSharedPreferences("eva.extension-credentials", Context.MODE_PRIVATE)

    enum class Status { NOT_SET, SET, OTHER_SIGNER }

    private class Stored(
        val binding: String?,
        val value: String,
    )

    /** Bumped whenever a value is saved or removed, so settings re-read [status]. */
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    fun status(
        identity: ExtensionIdentity,
        name: String,
    ): Status {
        val stored = read(identity.packageName, name) ?: return Status.NOT_SET
        return if (bound(stored, identity)) Status.SET else Status.OTHER_SIGNER
    }

    /** Saves [value] for [identity]; returns why it was refused, or null. */
    fun save(
        identity: ExtensionIdentity,
        spec: CredentialSpec,
        value: String,
    ): String? {
        if (value.isEmpty()) return "Enter ${spec.title} before saving. To remove it, use Clear."
        if (value.length > MAX_VALUE_CHARS) return "${spec.title} can be at most $MAX_VALUE_CHARS characters."
        val id = EvaConfigurationCodec.extensionSecretId(identity.packageName, spec.name)
        val key = key(identity.packageName, spec.name)
        secrets.write(key, value)
        commit { putString(BINDING + key, binding(identity)) }
        index(saved() + id)
        changed(id)
        return null
    }

    fun clear(
        packageName: String,
        name: String,
    ) {
        val id = EvaConfigurationCodec.extensionSecretId(packageName, name)
        val key = key(packageName, name)
        secrets.clear(key)
        commit { remove(BINDING + key) }
        index(saved() - id)
        changed(id)
    }

    /** `extension/<package>/<name>` references this phone holds a value for. */
    fun saved(): Set<String> =
        prefs
            .getStringSet(SAVED, emptySet())
            .orEmpty()
            .filter { id ->
                parse(id)?.let { read(it.first, it.second) } !=
                    null
            }.toSet()

    fun hasValue(id: String): Boolean = parse(id)?.let { read(it.first, it.second) } != null

    override fun values(
        identity: ExtensionIdentity,
        declared: List<CredentialSpec>,
    ): CredentialValues {
        val values = mutableMapOf<String, String>()
        for (spec in declared) {
            val stored = read(identity.packageName, spec.name) ?: continue
            if (!bound(stored, identity)) {
                return CredentialValues.Refused(
                    "${spec.title} was saved for a differently signed or reinstalled copy of ${identity.packageName}, so EVA " +
                        "will not send it. Re-enter it under Extensions > Installed.",
                )
            }
            values[spec.name] = stored.value
        }
        return CredentialValues.Ready(values)
    }

    private fun bound(
        stored: Stored,
        identity: ExtensionIdentity,
    ) = stored.binding == binding(identity)

    private fun binding(identity: ExtensionIdentity) = "${identity.user}:${identity.signer}"

    private fun read(
        packageName: String,
        name: String,
    ): Stored? {
        val key = key(packageName, name)
        return secrets.read(key)?.let { Stored(prefs.getString(BINDING + key, null), it) }
    }

    private fun index(ids: Set<String>) = commit { putStringSet(SAVED, ids) }

    @SuppressLint("UseKtx")
    private fun commit(change: android.content.SharedPreferences.Editor.() -> Unit) {
        check(prefs.edit().apply(change).commit()) { "Could not save extension credentials." }
    }

    private fun changed(id: String) {
        mutableRevision.value++
        onCredentialChanged(id)
    }

    private fun parse(id: String): Pair<String, String>? =
        id
            .takeIf(EvaConfigurationCodec::isExtensionSecretId)
            ?.removePrefix(EvaConfigurationCodec.EXTENSION_SECRET_PREFIX)
            ?.let { it.substringBeforeLast("/") to it.substringAfterLast("/") }

    private fun key(
        packageName: String,
        name: String,
    ) = "extension:$packageName:$name"

    private companion object {
        const val SAVED = "saved"
        const val BINDING = "binding:"
        const val MAX_VALUE_CHARS = 4096
    }
}
