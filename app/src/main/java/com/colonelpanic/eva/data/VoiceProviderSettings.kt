package com.colonelpanic.eva.data

import android.annotation.SuppressLint
import android.content.Context
import com.colonelpanic.eva.providers.grok.GrokVoiceConfiguration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class VoiceProviderSettings(
    context: Context,
    private val onChanged: () -> Unit = {},
    private val onCredentialChanged: (String) -> Unit = {},
) {
    data class Selection(
        val provider: String = "openai",
        val grok: GrokVoiceConfiguration = GrokVoiceConfiguration(),
    ) {
        init {
            require(provider in listOf("openai", "grok")) { "Unknown assistant voice provider: $provider" }
        }
    }

    private val prefs = context.getSharedPreferences("eva.voice", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    private val mutable =
        MutableStateFlow(
            Selection(
                prefs.getString("provider", "openai")!!,
                GrokVoiceConfiguration(
                    prefs.getString("grok.model", "grok-voice-3.0")!!,
                    prefs.getString("grok.voice", "eve")!!,
                    prefs.getString("grok.effort", "high")!!,
                ),
            ),
        )
    val state = mutable.asStateFlow()
    private val mutableHasKey = MutableStateFlow(apiKey() != null)
    val hasApiKey = mutableHasKey.asStateFlow()

    @SuppressLint("UseKtx")
    fun save(selection: Selection) {
        check(
            prefs
                .edit()
                .putString("provider", selection.provider)
                .putString("grok.model", selection.grok.model)
                .putString("grok.voice", selection.grok.voice)
                .putString("grok.effort", selection.grok.reasoningEffort)
                .commit(),
        ) {
            "Could not save assistant voice settings."
        }
        mutable.value = selection
        onChanged()
    }

    fun apiKey(): String? = secrets.read("grok.apiKey")

    fun requireApiKey(): String = apiKey() ?: error("Add an xAI API key in Settings > Account before starting Grok voice.")

    fun saveApiKey(value: String) {
        val key = value.trim()
        require(key.length in 20..512 && key.none(Char::isWhitespace)) { "That does not look like an xAI API key." }
        secrets.write("grok.apiKey", key)
        mutableHasKey.value = true
        onCredentialChanged(CREDENTIAL)
        onChanged()
    }

    fun clearApiKey() {
        secrets.clear("grok.apiKey")
        mutableHasKey.value = false
        onCredentialChanged(CREDENTIAL)
        onChanged()
    }

    companion object {
        const val CREDENTIAL = "provider/xai-api"
    }
}
