package com.colonelpanic.eva.data

import android.annotation.SuppressLint
import android.content.Context
import com.colonelpanic.eva.capability.extensions.CredentialSpec
import com.colonelpanic.eva.capability.extensions.CredentialValues
import com.colonelpanic.eva.capability.extensions.CredentialVerdict
import com.colonelpanic.eva.capability.extensions.ExtensionCredentialSource
import com.colonelpanic.eva.capability.extensions.ExtensionIdentity
import com.colonelpanic.eva.data.configuration.EvaConfigurationCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.concurrent.ConcurrentHashMap

/**
 * Values installed extensions declare in their descriptor's `credentials`, such as a key
 * passphrase. Each is saved in this phone's [SecretStore], beside the user and signer it was
 * entered for, and is delivered only to that same identity. The portable configuration carries
 * only `extension/<package>/<name>` references.
 *
 * What the provider last said about the saved value, from a check or a call that used it, is
 * device-local state kept beside it. Saving or clearing the value forgets it.
 */
class ExtensionCredentialSettings(
    context: Context,
    private val onCredentialChanged: (String) -> Unit = {},
    private val secrets: SecretStore = SecretStore(context),
    private val now: () -> Long = System::currentTimeMillis,
) : ExtensionCredentialSource {
    private val prefs = context.getSharedPreferences("eva.extension-credentials", Context.MODE_PRIVATE)

    enum class Status { NOT_SET, SET, OTHER_SIGNER }

    /** The provider's [verdict] on the saved value at [atMillis], from a check or, with [onUse], a call that used it. */
    data class Check(
        val verdict: CredentialVerdict,
        val onUse: Boolean,
        val atMillis: Long,
    )

    data class State(
        val status: Status,
        val check: Check? = null,
        val checking: Boolean = false,
    )

    private val checking = ConcurrentHashMap.newKeySet<String>()

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

    fun state(
        identity: ExtensionIdentity,
        name: String,
    ): State {
        val status = status(identity, name)
        if (status != Status.SET) return State(status)
        val key = key(identity.packageName, name)
        return State(status, prefs.getString(CHECK + key, null)?.let(::decode), key in checking)
    }

    /**
     * Asks the provider about the value saved for [identity] through [ask], which receives it. A
     * value replaced while it was being asked about is asked about in turn, so the verdict kept is
     * always for the value now saved.
     */
    suspend fun check(
        identity: ExtensionIdentity,
        spec: CredentialSpec,
        ask: suspend (String) -> CredentialVerdict,
    ) {
        val key = key(identity.packageName, spec.name)
        if (!checking.add(key)) return
        bump()
        try {
            do {
                val stored = read(identity.packageName, spec.name)?.takeIf { bound(it, identity) } ?: break
            } while (!record(identity, spec.name, stored.value, Check(ask(stored.value), onUse = false, atMillis = now())))
        } finally {
            checking.remove(key)
            bump()
        }
    }

    override fun rejected(
        identity: ExtensionIdentity,
        delivered: Map<String, String>,
        message: String,
    ) {
        val at = now()
        delivered.forEach { (name, value) -> record(identity, name, value, Check(CredentialVerdict.Rejected(message), onUse = true, at)) }
    }

    /** Keeps [check] if [value] is still the saved value; returns whether it was. */
    @Synchronized
    private fun record(
        identity: ExtensionIdentity,
        name: String,
        value: String,
        check: Check,
    ): Boolean {
        val stored = read(identity.packageName, name)
        if (stored == null || !bound(stored, identity) || stored.value != value) return false
        commit { putString(CHECK + key(identity.packageName, name), encode(check, value)) }
        bump()
        return true
    }

    private fun bump() = mutableRevision.update { it + 1 }

    /** Saves [value] for [identity]; returns why it was refused, or null. */
    @Synchronized
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
        commit {
            putString(BINDING + key, binding(identity))
            remove(CHECK + key)
        }
        index(saved() + id)
        changed(id)
        return null
    }

    @Synchronized
    fun clear(
        packageName: String,
        name: String,
    ) {
        val id = EvaConfigurationCodec.extensionSecretId(packageName, name)
        val key = key(packageName, name)
        secrets.clear(key)
        commit {
            remove(BINDING + key)
            remove(CHECK + key)
        }
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
        bump()
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

    /** The provider's text is kept with any copy of [value] masked. */
    private fun encode(
        check: Check,
        value: String,
    ): String =
        JsonObject(
            mapOf(
                "verdict" to
                    JsonPrimitive(
                        when (check.verdict) {
                            is CredentialVerdict.Accepted -> "accepted"
                            is CredentialVerdict.Rejected -> "rejected"
                            is CredentialVerdict.Unverified -> "unverified"
                        },
                    ),
                "message" to
                    JsonPrimitive(
                        check.verdict.message
                            .replace(value, "[credential]")
                            .take(MAX_MESSAGE_CHARS),
                    ),
                "onUse" to JsonPrimitive(check.onUse),
                "at" to JsonPrimitive(check.atMillis),
            ),
        ).toString()

    private fun decode(json: String): Check? =
        runCatching {
            val fields = Json.parseToJsonElement(json).jsonObject
            val message = fields.getValue("message").jsonPrimitive.content
            val verdict =
                when (fields.getValue("verdict").jsonPrimitive.content) {
                    "accepted" -> CredentialVerdict.Accepted(message)
                    "rejected" -> CredentialVerdict.Rejected(message)
                    else -> CredentialVerdict.Unverified(message)
                }
            Check(verdict, fields.getValue("onUse").jsonPrimitive.content == "true", fields.getValue("at").jsonPrimitive.long)
        }.getOrNull()

    private companion object {
        const val SAVED = "saved"
        const val BINDING = "binding:"
        const val CHECK = "check:"
        const val MAX_MESSAGE_CHARS = 2000
        const val MAX_VALUE_CHARS = 4096
    }
}
