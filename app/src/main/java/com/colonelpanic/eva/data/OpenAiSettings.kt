package com.colonelpanic.eva.data

import android.content.Context
import androidx.core.content.edit
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
    private val mutableTextModel = MutableStateFlow(prefs.getString(TEXT_MODEL, null) ?: OpenAiModels.TEXT)
    private val mutableRealtimeModel = MutableStateFlow(prefs.getString(REALTIME_MODEL, null) ?: OpenAiModels.REALTIME)
    private val mutableReasoningEffort =
        MutableStateFlow(
            prefs.getString(REASONING_EFFORT, null)?.takeIf { it in OpenAiModels.REASONING_EFFORTS } ?: OpenAiModels.REASONING_EFFORT,
        )
    private val mutableVoiceLookupRetries = MutableStateFlow(prefs.getInt(VOICE_LOOKUP_RETRIES, DEFAULT_VOICE_LOOKUP_RETRIES))

    /** Model used for typed turns. Changing it applies to the next connection. */
    val textModelFlow = mutableTextModel.asStateFlow()
    val realtimeModelFlow = mutableRealtimeModel.asStateFlow()
    val reasoningEffortFlow = mutableReasoningEffort.asStateFlow()
    val voiceLookupRetriesFlow = mutableVoiceLookupRetries.asStateFlow()

    val realtimeModel: String get() = mutableRealtimeModel.value
    val textModel: String get() = mutableTextModel.value
    val reasoningEffort: String get() = mutableReasoningEffort.value
    val voiceLookupRetries: Int get() = mutableVoiceLookupRetries.value

    fun saveTextModel(value: String) = saveModel(TEXT_MODEL, value, OpenAiModels.TEXT, mutableTextModel)

    fun saveRealtimeModel(value: String) = saveModel(REALTIME_MODEL, value, OpenAiModels.REALTIME, mutableRealtimeModel)

    /** Reasoning effort is a closed set, so only a known value is stored. */
    fun saveReasoningEffort(value: String) {
        val trimmed = value.trim()
        require(trimmed in OpenAiModels.REASONING_EFFORTS) { "Unknown reasoning effort." }
        prefs.edit { putString(REASONING_EFFORT, trimmed) }
        mutableReasoningEffort.value = trimmed
    }

    fun saveVoiceLookupRetries(value: Int) {
        require(value in MIN_VOICE_LOOKUP_RETRIES..MAX_VOICE_LOOKUP_RETRIES) { "Voice lookup retries must be between 0 and 10." }
        prefs.edit { putInt(VOICE_LOOKUP_RETRIES, value) }
        mutableVoiceLookupRetries.value = value
    }

    /** A blank value restores the built-in default rather than storing an unusable name. */
    private fun saveModel(
        key: String,
        value: String,
        fallback: String,
        target: MutableStateFlow<String>,
    ) {
        val trimmed = value.trim()
        require(trimmed.isEmpty() || (trimmed.length <= 100 && trimmed.none { it.isWhitespace() })) { "That is not a model name." }
        val resolved = trimmed.ifEmpty { fallback }
        prefs.edit { if (trimmed.isEmpty()) remove(key) else putString(key, trimmed) }
        target.value = resolved
    }

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
        const val REASONING_EFFORT = "openai.reasoningEffort"
        const val VOICE_LOOKUP_RETRIES = "voice.lookupRetries"
        const val DEFAULT_VOICE_LOOKUP_RETRIES = 5
        const val MIN_VOICE_LOOKUP_RETRIES = 0
        const val MAX_VOICE_LOOKUP_RETRIES = 10
    }
}
