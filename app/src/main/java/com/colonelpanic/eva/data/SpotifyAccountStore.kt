package com.colonelpanic.eva.data

import android.content.Context
import androidx.core.content.edit
import com.colonelpanic.eva.providers.spotify.SpotifyLogin
import com.colonelpanic.eva.providers.spotify.SpotifyProfile
import com.colonelpanic.eva.providers.spotify.SpotifyTokenRefresher
import com.colonelpanic.eva.providers.spotify.SpotifyTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class SpotifyAccount(
    val displayName: String?,
    val product: String?,
) {
    val description: String
        get() {
            val name = displayName?.takeIf { it.isNotBlank() }
            val label =
                when {
                    product.equals("premium", ignoreCase = true) -> "Spotify Premium"
                    name != null -> "Spotify"
                    else -> "Spotify account"
                }
            return listOfNotNull(label, name).joinToString(" · ")
        }
}

internal interface SpotifyAccountStorage {
    fun clientId(): String?

    fun saveClientId(value: String?)

    fun account(): String?

    fun saveAccount(value: String)

    fun clearAccount()
}

private class AndroidSpotifyAccountStorage(
    context: Context,
) : SpotifyAccountStorage {
    private val secrets = SecretStore(context)
    private val prefs = context.applicationContext.getSharedPreferences("eva.settings", Context.MODE_PRIVATE)

    override fun clientId(): String? = prefs.getString(CLIENT_ID, null)

    override fun saveClientId(value: String?) {
        prefs.edit { if (value == null) remove(CLIENT_ID) else putString(CLIENT_ID, value) }
    }

    override fun account(): String? = secrets.read(TOKENS)

    override fun saveAccount(value: String) = secrets.write(TOKENS, value)

    override fun clearAccount() = secrets.clear(TOKENS)

    private companion object {
        const val CLIENT_ID = "spotify.client_id"
        const val TOKENS = "spotify.tokens"
    }
}

class SpotifyAccountStore internal constructor(
    private val storage: SpotifyAccountStorage,
    private val refresher: SpotifyTokenRefresher,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(
        context: Context,
        login: SpotifyLogin = SpotifyLogin(),
        now: () -> Long = System::currentTimeMillis,
    ) : this(AndroidSpotifyAccountStorage(context), login, now)

    private val mutex = Mutex()
    private val mutableClientId = MutableStateFlow(storage.clientId())
    private val mutableAccount = MutableStateFlow(stored()?.profile?.account())

    val clientId: StateFlow<String?> = mutableClientId.asStateFlow()
    val account: StateFlow<SpotifyAccount?> = mutableAccount.asStateFlow()

    fun saveClientId(value: String) {
        val trimmed = value.trim()
        storage.saveClientId(trimmed.ifBlank { null })
        mutableClientId.value = trimmed.ifBlank { null }
    }

    fun save(
        tokens: SpotifyTokens,
        profile: SpotifyProfile,
    ) {
        storage.saveAccount(StoredSpotify(tokens, profile).toJson().toString())
        mutableAccount.value = profile.account()
    }

    fun clear() {
        storage.clearAccount()
        mutableAccount.value = null
    }

    suspend fun accessToken(): String =
        mutex.withLock {
            val clientId = mutableClientId.value ?: error(CONNECT_MESSAGE)
            val stored = stored() ?: error(CONNECT_MESSAGE)
            if (stored.tokens.expiresAt - now() > REFRESH_MARGIN_MILLIS) return@withLock stored.tokens.accessToken
            val refreshed =
                runCatching { refresher.refresh(clientId, stored.tokens) }
                    .getOrElse { error(EXPIRED_MESSAGE) }
            save(refreshed, stored.profile)
            refreshed.accessToken
        }

    private fun stored(): StoredSpotify? {
        val raw = storage.account() ?: return null
        return runCatching { Json.parseToJsonElement(raw).jsonObject.toStoredSpotify() }.getOrNull()
    }

    companion object {
        const val CONNECT_MESSAGE = "Connect Spotify in EVA's settings."
        const val EXPIRED_MESSAGE = "That Spotify connection has expired. Connect Spotify again."
        private const val REFRESH_MARGIN_MILLIS = 2 * 60 * 1000L
    }
}

private data class StoredSpotify(
    val tokens: SpotifyTokens,
    val profile: SpotifyProfile,
)

private fun SpotifyProfile.account() = SpotifyAccount(displayName, product)

private fun StoredSpotify.toJson(): JsonObject =
    buildJsonObject {
        put("access_token", tokens.accessToken)
        put("refresh_token", tokens.refreshToken)
        put("expires_at", tokens.expiresAt)
        tokens.scope?.let { put("scope", it) }
        profile.displayName?.let { put("display_name", it) }
        profile.product?.let { put("product", it) }
    }

private fun JsonObject.toStoredSpotify() =
    StoredSpotify(
        tokens =
            SpotifyTokens(
                accessToken = requireNotNull(text("access_token")),
                refreshToken = requireNotNull(text("refresh_token")),
                expiresAt = text("expires_at")?.toLongOrNull() ?: 0L,
                scope = text("scope"),
            ),
        profile = SpotifyProfile(text("display_name"), text("product")),
    )

private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content
