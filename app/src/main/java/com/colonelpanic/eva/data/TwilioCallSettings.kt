package com.colonelpanic.eva.data

import android.annotation.SuppressLint
import android.content.Context
import com.colonelpanic.eva.adapters.android.TwilioCallAccount
import com.colonelpanic.eva.call.TwilioApiKey
import com.colonelpanic.eva.data.configuration.EvaConfigurationCodec
import com.colonelpanic.eva.data.configuration.TwilioCallsConfiguration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Twilio call route's setup. The account, TwiML app and caller ID are portable configuration
 * (`capabilities.twilioCalls`); the API key that mints access tokens is saved only in this phone's
 * [SecretStore], so a restored configuration shows up asking for it.
 */
class TwilioCallSettings(
    context: Context,
    private val onChanged: () -> Unit = {},
    private val onCredentialChanged: (String) -> Unit = {},
    private val secrets: SecretStore = SecretStore(context),
) : TwilioCallAccount {
    private val prefs = context.getSharedPreferences("eva.twilio-calls", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(stored())
    val state = mutable.asStateFlow()

    /** Bumped whenever the API key is saved or removed, which changes nothing in [state]. */
    private val mutableKeyRevision = MutableStateFlow(0L)
    val keyRevision = mutableKeyRevision.asStateFlow()

    override fun configuration(): TwilioCallsConfiguration? = mutable.value

    override fun apiKey(): TwilioApiKey? =
        secrets.read(SECRET)?.let { encoded ->
            runCatching { Json.decodeFromString<StoredKey>(encoded).let { TwilioApiKey(it.sid, it.secret) } }.getOrNull()
        }

    /**
     * Saves the route from the settings screen. A blank key SID and secret keep the saved key; a new
     * setup may be saved without one, and is then reported as needing it on this phone.
     */
    fun save(
        configuration: TwilioCallsConfiguration,
        keySid: String,
        keySecret: String,
    ) {
        val sid = keySid.trim()
        val secret = keySecret.trim()
        val key =
            when {
                sid.isEmpty() && secret.isEmpty() -> null
                sid.isEmpty() || secret.isEmpty() -> throw IllegalArgumentException("Enter both the API key SID and its secret.")
                else -> TwilioApiKey(sid, secret)
            }
        replace(configuration)
        if (key != null) {
            secrets.write(SECRET, Json.encodeToString(StoredKey(key.sid, key.secret)))
            mutableKeyRevision.value++
            onCredentialChanged(EvaConfigurationCodec.TWILIO_CALLS_SECRET)
        }
    }

    /** Turns the route off and forgets this phone's API key. */
    fun remove() {
        secrets.clear(SECRET)
        mutableKeyRevision.value++
        replace(null)
        onCredentialChanged(EvaConfigurationCodec.TWILIO_CALLS_SECRET)
    }

    /** Restores the portable part; the API key is device-local and stays where it is. */
    fun replace(configuration: TwilioCallsConfiguration?) {
        commit {
            if (configuration == null) {
                remove(CONFIGURATION)
            } else {
                putString(CONFIGURATION, Json.encodeToString(TwilioCallsConfiguration.serializer(), configuration))
            }
        }
        mutable.value = configuration
        onChanged()
    }

    private fun stored(): TwilioCallsConfiguration? =
        prefs.getString(CONFIGURATION, null)?.let { encoded ->
            runCatching { Json.decodeFromString(TwilioCallsConfiguration.serializer(), encoded) }.getOrNull()
        }

    @SuppressLint("UseKtx")
    private fun commit(change: android.content.SharedPreferences.Editor.() -> Unit) {
        check(prefs.edit().apply(change).commit()) { "Could not save the Twilio call settings." }
    }

    @Serializable
    private class StoredKey(
        val sid: String,
        val secret: String,
    )

    private companion object {
        const val CONFIGURATION = "configuration"
        const val SECRET = "provider:twilio-calls"
    }
}
