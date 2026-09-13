package com.colonelpanic.eva.providers.openai

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/** Which conversation leg a model can serve. */
enum class ModelKind { TEXT, REALTIME }

/**
 * Lists the models this account can actually use, so the picker offers real choices
 * rather than a list baked into the app.
 */
class OpenAiModelCatalog(
    private val client: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = OpenAiModels.BASE_URL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun load(apiKey: String): Map<ModelKind, List<String>> =
        withContext(ioDispatcher) {
            val request =
                Request
                    .Builder()
                    .url("$baseUrl/v1/models")
                    .header("Authorization", "Bearer $apiKey")
                    .build()
            val body =
                client.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    check(response.isSuccessful) { openAiErrorMessage(response.code, text, "the model list") }
                    Json.parseToJsonElement(text).jsonObject
                }
            val ids =
                body["data"]
                    ?.jsonArray
                    .orEmpty()
                    .mapNotNull { (it as? JsonObject)?.str("id") }
            classify(ids)
        }

    companion object {
        /**
         * Realtime sessions only accept speech-to-speech models; offering a text model there
         * produces a confusing server rejection at connect time. Names are matched rather than
         * enumerated so new models appear without an app update.
         */
        fun classify(ids: List<String>): Map<ModelKind, List<String>> {
            val realtime =
                ids
                    .filter { it.contains("realtime") || it.startsWith("gpt-live") || it.contains("-audio") }
                    .filterNot { it.contains("transcribe") || it.contains("tts") }
            val text =
                ids
                    .filter { it.startsWith("gpt-") || it.startsWith("o1") || it.startsWith("o3") || it.startsWith("o4") }
                    .filterNot { id ->
                        id in realtime ||
                            listOf("transcribe", "tts", "audio", "image", "embedding", "moderation", "search", "instruct", "dall-e")
                                .any { id.contains(it) }
                    }
            return mapOf(
                ModelKind.TEXT to text.sorted().distinct(),
                ModelKind.REALTIME to realtime.sorted().distinct(),
            )
        }
    }
}
