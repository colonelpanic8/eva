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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun load(access: OpenAiAccess): Map<ModelKind, List<String>> =
        withContext(ioDispatcher) {
            val request = access.authorize(Request.Builder().url(access.modelsUrl)).build()
            val body =
                client.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    check(response.isSuccessful) { openAiErrorMessage(response.code, text, "the model list") }
                    Json.parseToJsonElement(text).jsonObject
                }
            classify(identifiers(body))
        }

    companion object {
        /** The public API lists `data[].id`; a subscription account lists `models[].slug`. */
        internal fun identifiers(body: JsonObject): List<String> =
            (body["data"] ?: body["models"])
                ?.jsonArray
                .orEmpty()
                .mapNotNull { (it as? JsonObject)?.let { entry -> entry.str("id") ?: entry.str("slug") } }

        /**
         * Realtime sessions only accept speech-to-speech models; offering a text model there
         * produces a confusing server rejection at connect time. Names are matched rather than
         * enumerated so new models appear without an app update. The text list keeps every
         * actual candidate that is not speech-only or a known non-chat model, so account
         * backends that list slugs outside the `gpt-`/`o` families still show up.
         */
        fun classify(ids: List<String>): Map<ModelKind, List<String>> {
            val realtime =
                ids
                    .filter { it.contains("realtime") || it.startsWith("gpt-live") || it.contains("-audio") }
                    .filterNot { it.contains("transcribe") || it.contains("tts") }
            val text =
                ids.filterNot { id ->
                    id in realtime || NON_TEXT_HINTS.any { id.contains(it) }
                }
            return mapOf(
                ModelKind.TEXT to text.sorted().distinct(),
                ModelKind.REALTIME to realtime.sorted().distinct(),
            )
        }

        private val NON_TEXT_HINTS =
            listOf(
                "transcribe",
                "tts",
                "whisper",
                "audio",
                "image",
                "embedding",
                "moderation",
                "search",
                "instruct",
                "dall-e",
                "realtime",
                "live",
            )
    }
}
