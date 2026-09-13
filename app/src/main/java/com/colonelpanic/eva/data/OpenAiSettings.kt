package com.colonelpanic.eva.data

import android.content.Context
import com.colonelpanic.eva.providers.openai.OpenAiModels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The phone's own provider credentials. Nothing here touches a workstation. */
class OpenAiSettings(
    context: Context,
) {
    private val secrets = SecretStore(context)
    private val prefs = context.applicationContext.getSharedPreferences("eva.settings", Context.MODE_PRIVATE)
    private val mutableHasKey = MutableStateFlow(secrets.read(API_KEY) != null)
    val hasApiKey = mutableHasKey.asStateFlow()

    val realtimeModel: String get() = prefs.getString(REALTIME_MODEL, null) ?: OpenAiModels.REALTIME
    val textModel: String get() = prefs.getString(TEXT_MODEL, null) ?: OpenAiModels.TEXT

    fun apiKey(): String? = secrets.read(API_KEY)

    fun requireApiKey(): String = apiKey() ?: error("Add an OpenAI API key, or a paired host link.")

    fun saveApiKey(value: String) {
        val trimmed = value.trim()
        require(trimmed.length in 20..512 && trimmed.none { it.isWhitespace() }) { "That does not look like an API key." }
        secrets.write(API_KEY, trimmed)
        mutableHasKey.value = true
    }

    fun clearApiKey() {
        secrets.clear(API_KEY)
        mutableHasKey.value = false
    }

    private companion object {
        const val API_KEY = "openai.apiKey"
        const val REALTIME_MODEL = "openai.realtimeModel"
        const val TEXT_MODEL = "openai.textModel"
    }
}
