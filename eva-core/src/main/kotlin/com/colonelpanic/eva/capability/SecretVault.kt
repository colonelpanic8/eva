package com.colonelpanic.eva.capability

import com.colonelpanic.eva.devicecontrol.Secret
import com.colonelpanic.eva.devicecontrol.SecretValues
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.SecureRandom

/**
 * Secrets an extension returned, held in memory only: never on disk, in the journal, in traces, or
 * in anything a model receives. Models hold opaque handles, valid within the conversation thread
 * that received them and the device tasks it starts, until that thread is cleared or the process dies.
 */
class SecretVault(
    private val random: SecureRandom = SecureRandom(),
) {
    private class Entry(
        val scope: String,
        val secret: Secret,
    )

    private val entries = LinkedHashMap<String, Entry>()

    fun store(
        threadId: String?,
        value: String,
        label: String,
    ): String =
        synchronized(entries) {
            val handle = PREFIX + ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            entries[handle] = Entry(scope(threadId), Secret(value, label))
            handle
        }

    fun resolve(
        threadId: String?,
        handle: String,
    ): Secret? = synchronized(entries) { entries[handle.trim()]?.takeIf { it.scope == scope(threadId) }?.secret }

    /** Forgets every thread's secrets except those of [threadIds], whose work is still running. */
    fun retain(threadIds: Set<String>) =
        synchronized(entries) {
            entries.values.removeAll { it.scope !in threadIds }
            Unit
        }

    fun mask(text: String): String {
        val values =
            synchronized(entries) {
                entries.values
                    .map { it.secret.value }
                    .filter { it.length >= MIN_MASKED }
                    .distinct()
            }.sortedByDescending(String::length)
        return values.fold(text) { masked, value -> masked.replace(value, SecretValues.PLACEHOLDER) }
    }

    fun mask(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.mapValues { mask(it.value) })
            is JsonArray -> JsonArray(element.map(::mask))
            is JsonPrimitive -> if (element.isString) JsonPrimitive(mask(element.content)) else element
        }

    /** The view a device task started from [threadId] resolves through. */
    fun scoped(threadId: String?): SecretValues =
        object : SecretValues {
            override fun resolve(handle: String) = this@SecretVault.resolve(threadId, handle)

            override fun mask(text: String) = this@SecretVault.mask(text)
        }

    private fun scope(threadId: String?) = threadId.orEmpty()

    companion object {
        const val PREFIX = "secret:"

        /** Shorter values would garble ordinary screen text wherever they happened to occur. */
        const val MIN_MASKED = 4
    }
}
