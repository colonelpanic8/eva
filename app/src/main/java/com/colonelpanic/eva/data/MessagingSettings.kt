package com.colonelpanic.eva.data

import android.annotation.SuppressLint
import android.content.Context
import com.colonelpanic.eva.data.configuration.MessagingBridgeDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

data class MessagingPreferences(
    val enabled: Boolean = false,
    val replies: Set<String> = emptySet(),
)

class MessagingSettings(
    context: Context,
    private val onChanged: () -> Unit = {},
    private val onReplyChanged: (String) -> Unit = {},
    private val secrets: SecretStore = SecretStore(context),
) {
    private val prefs = context.getSharedPreferences("eva.messaging", Context.MODE_PRIVATE)
    private val mutable =
        MutableStateFlow(MessagingPreferences(prefs.getBoolean("enabled", false), prefs.getStringSet("replies", emptySet())!!.toSet()))
    val state = mutable.asStateFlow()

    private val mutableLegacy = MutableStateFlow(legacy())

    /**
     * Bridges EVA 0.41 and 0.42 kept here, before messaging services became extension packages. They no
     * longer route anything; they stay listed so the user can re-create each one in the extension.
     */
    val legacyBridges = mutableLegacy.asStateFlow()

    fun enable(value: Boolean) {
        replace(mutable.value.copy(enabled = value))
    }

    fun allowReply(
        identity: String,
        value: Boolean,
    ) {
        val replies = if (value) mutable.value.replies + identity else mutable.value.replies - identity
        replace(mutable.value.copy(replies = replies))
        onReplyChanged(identity)
    }

    fun replace(value: MessagingPreferences) {
        commit {
            putBoolean("enabled", value.enabled)
            putStringSet("replies", value.replies)
        }
        mutable.value = value.copy(replies = value.replies.toSet())
        onChanged()
    }

    /** Forgets the retired bridges and the tokens saved for them. */
    fun dismissLegacyBridges() {
        mutableLegacy.value.keys.forEach { secrets.clear("messaging:$it:bearer") }
        commit { remove(LEGACY_BRIDGES) }
        mutableLegacy.value = emptyMap()
    }

    private fun legacy(): Map<String, MessagingBridgeDefinition> =
        prefs
            .getString(LEGACY_BRIDGES, null)
            ?.let { encoded ->
                runCatching { Json.decodeFromString<Map<String, MessagingBridgeDefinition>>(encoded) }.getOrNull()
            }.orEmpty()

    @SuppressLint("UseKtx")
    private fun commit(change: android.content.SharedPreferences.Editor.() -> Unit) {
        check(prefs.edit().apply(change).commit()) { "Could not save messaging settings." }
    }

    private companion object {
        const val LEGACY_BRIDGES = "bridges"
    }
}
