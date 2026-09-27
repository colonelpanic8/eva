package com.colonelpanic.eva.data

import android.annotation.SuppressLint
import android.content.Context
import com.colonelpanic.eva.adapters.declarative.BearerCredential
import com.colonelpanic.eva.adapters.declarative.serverOrigin
import com.colonelpanic.eva.data.configuration.EvaConfigurationCodec
import com.colonelpanic.eva.data.configuration.MessagingBridgeDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

data class MessagingPreferences(
    val enabled: Boolean = false,
    val replies: Set<String> = emptySet(),
    /** Linked messaging accounts by service name; portable, unlike their tokens. */
    val bridges: Map<String, MessagingBridgeDefinition> = emptyMap(),
)

class MessagingSettings(
    context: Context,
    private val onChanged: () -> Unit = {},
    private val onReplyChanged: (String) -> Unit = {},
    private val onCredentialChanged: (String) -> Unit = {},
    private val secrets: SecretStore = SecretStore(context),
) {
    private val prefs = context.getSharedPreferences("eva.messaging", Context.MODE_PRIVATE)
    private val mutable =
        MutableStateFlow(
            MessagingPreferences(
                prefs.getBoolean("enabled", false),
                prefs.getStringSet("replies", emptySet())!!.toSet(),
                storedBridges(),
            ),
        )
    val state = mutable.asStateFlow()

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

    /**
     * Adds or edits a bridge. A blank token keeps the saved one, which stays usable only while the
     * origin it was saved for is unchanged; the reference is reported as needing provisioning otherwise.
     */
    fun saveBridge(
        name: String,
        label: String,
        origin: String,
        token: String,
    ) {
        val service = name.trim()
        require(EvaConfigurationCodec.isMessagingBridgeName(service)) {
            "Use a lowercase service name with letters, numbers, and hyphens; sms and notifications are taken."
        }
        val title = label.trim().ifEmpty { service }
        require(title.length <= 100 && title.none(Char::isISOControl)) { "Enter a shorter label." }
        val approved = serverOrigin(origin)
        val credential = token.trim().takeIf(String::isNotEmpty)?.let { BearerCredential.create(approved, it) }
        val reference = EvaConfigurationCodec.messagingSecretId(service)
        replace(mutable.value.copy(bridges = mutable.value.bridges + (service to MessagingBridgeDefinition(title, approved))))
        if (credential != null) {
            secrets.write(secretKey(reference), credential.encode())
            onCredentialChanged(reference)
        }
    }

    fun removeBridge(name: String) {
        val reference = EvaConfigurationCodec.messagingSecretId(name)
        secrets.clear(secretKey(reference))
        replace(mutable.value.copy(bridges = mutable.value.bridges - name))
        onCredentialChanged(reference)
    }

    /** The saved token for a configured bridge, only while it was saved for the bridge's current origin. */
    fun bridgeCredential(name: String): BearerCredential? {
        val bridge = mutable.value.bridges[name] ?: return null
        return storedCredential(name)?.takeIf { it.origin == bridge.origin }
    }

    /** Credential references a configuration needs that this device cannot currently satisfy. */
    fun missingBridgeCredentials(bridges: Map<String, MessagingBridgeDefinition>): List<String> =
        bridges.mapNotNull { (name, bridge) ->
            EvaConfigurationCodec.messagingSecretId(name).takeIf { storedCredential(name)?.origin != bridge.origin }
        }

    /** Restores the portable settings; saved tokens are device-local and stay where they are. */
    fun replace(value: MessagingPreferences) {
        commit {
            putBoolean("enabled", value.enabled)
            putStringSet("replies", value.replies)
            if (value.bridges.isEmpty()) {
                remove(BRIDGES)
            } else {
                putString(BRIDGES, Json.encodeToString<Map<String, MessagingBridgeDefinition>>(value.bridges.toSortedMap()))
            }
        }
        mutable.value = value.copy(replies = value.replies.toSet(), bridges = value.bridges.toSortedMap())
        onChanged()
    }

    private fun storedBridges(): Map<String, MessagingBridgeDefinition> =
        prefs
            .getString(BRIDGES, null)
            ?.let { encoded ->
                runCatching { Json.decodeFromString<Map<String, MessagingBridgeDefinition>>(encoded) }.getOrNull()
            }.orEmpty()

    private fun storedCredential(name: String): BearerCredential? =
        secrets.read(secretKey(EvaConfigurationCodec.messagingSecretId(name)))?.let {
            runCatching { BearerCredential.decode(it) }.getOrNull()
        }

    private fun secretKey(reference: String) = reference.replace("/", ":")

    @SuppressLint("UseKtx")
    private fun commit(change: android.content.SharedPreferences.Editor.() -> Unit) {
        check(prefs.edit().apply(change).commit()) { "Could not save messaging settings." }
    }

    private companion object {
        const val BRIDGES = "bridges"
    }
}
