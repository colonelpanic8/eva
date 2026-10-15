package com.colonelpanic.eva.data

import android.annotation.SuppressLint
import android.content.Context
import com.colonelpanic.eva.call.CallCredential
import com.colonelpanic.eva.call.CallCredentialResolver
import com.colonelpanic.eva.call.CallCredentialUnavailable
import com.colonelpanic.eva.data.configuration.EvaConfigurationCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Keypad secrets the owner saves for delegated phone calls, such as an account number or PIN. The
 * reference and label are portable configuration; the value is saved only in this phone's
 * [SecretStore], so a restored reference shows up asking for it.
 */
class CallCredentialSettings(
    context: Context,
    private val onChanged: () -> Unit = {},
    private val onCredentialChanged: (String) -> Unit = {},
    private val secrets: SecretStore = SecretStore(context),
) : CallCredentialResolver {
    private val prefs = context.getSharedPreferences("eva.call-credentials", Context.MODE_PRIVATE)

    /** Reference to label. */
    private val mutable = MutableStateFlow(storedLabels())
    val state = mutable.asStateFlow()

    /** Bumped whenever a value is saved or removed, which changes nothing in [state]. */
    private val mutableValueRevision = MutableStateFlow(0L)
    val valueRevision = mutableValueRevision.asStateFlow()

    /**
     * Adds or edits a credential. A blank value keeps the saved one; a new credential may be saved
     * without one, and is then reported as needing its value on this phone.
     */
    fun save(
        reference: String,
        label: String,
        value: String,
    ) {
        val id = reference.trim()
        require(EvaConfigurationCodec.isCallCredentialReference(id)) {
            "Use a reference of lowercase letters and digits, optionally split by -, _, . or /, such as bank/pin."
        }
        val title = label.trim()
        require(EvaConfigurationCodec.isCallCredentialLabel(title)) { "Enter a label of at most 100 characters." }
        val keys = value.filterNot(Char::isWhitespace)
        require(keys.all { it in KEYPAD }) { "The value can hold only digits, * and #, which is what a phone keypad sends." }
        replace(mutable.value + (id to title))
        if (keys.isNotEmpty()) {
            secrets.write(secretKey(id), keys)
            mutableValueRevision.value++
            onCredentialChanged(EvaConfigurationCodec.callSecretId(id))
        }
    }

    fun remove(reference: String) {
        secrets.clear(secretKey(reference))
        mutableValueRevision.value++
        replace(mutable.value - reference)
        onCredentialChanged(EvaConfigurationCodec.callSecretId(reference))
    }

    fun hasValue(reference: String): Boolean = secrets.read(secretKey(reference)) != null

    /** References whose value this phone does not hold. */
    fun missingValues(): Set<String> =
        mutable.value.keys
            .filterNot(::hasValue)
            .toSet()

    /** Restores the portable references; values are device-local and stay where they are. */
    fun replace(labels: Map<String, String>) {
        commit {
            if (labels.isEmpty()) remove(LABELS) else putString(LABELS, Json.encodeToString<Map<String, String>>(labels.toSortedMap()))
        }
        mutable.value = labels.toSortedMap()
        onChanged()
    }

    override fun configured(): List<CallCredential> = mutable.value.map { (reference, label) -> CallCredential(reference, label) }

    override suspend fun resolve(reference: String): CharArray {
        val label =
            mutable.value[reference]
                ?: throw CallCredentialUnavailable(
                    "No call credential is saved as $reference" +
                        (if (mutable.value.isEmpty()) "; none are saved" else "; saved: ${mutable.value.keys.joinToString()}") +
                        ". The owner adds it under Messaging > Call keypad credentials.",
                )
        val value =
            secrets.read(secretKey(reference))
                ?: throw CallCredentialUnavailable(
                    "Call credential $reference ($label) has no value on this phone. The owner enters it under " +
                        "Messaging > Call keypad credentials.",
                )
        if (value.isEmpty() || value.any { it !in KEYPAD }) {
            throw CallCredentialUnavailable(
                "Call credential $reference ($label) is not digits, * and # only. The owner re-enters it under " +
                    "Messaging > Call keypad credentials.",
            )
        }
        return value.toCharArray()
    }

    private fun storedLabels(): Map<String, String> =
        prefs
            .getString(LABELS, null)
            ?.let { encoded -> runCatching { Json.decodeFromString<Map<String, String>>(encoded) }.getOrNull() }
            .orEmpty()
            .toSortedMap()

    private fun secretKey(reference: String) = EvaConfigurationCodec.callSecretId(reference).replace("/", ":")

    @SuppressLint("UseKtx")
    private fun commit(change: android.content.SharedPreferences.Editor.() -> Unit) {
        check(prefs.edit().apply(change).commit()) { "Could not save call credentials." }
    }

    private companion object {
        const val LABELS = "labels"
        const val KEYPAD = "0123456789*#"
    }
}
