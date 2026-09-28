package com.colonelpanic.eva.providers.openai

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared cancellable HTTP/SSE exchange; credential storage stays outside the transport. */
internal suspend fun responsesPost(
    client: OkHttpClient,
    access: OpenAiAccess,
    payload: JsonObject,
    ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
): JsonObject {
    val request =
        access
            .authorize(Request.Builder().url(access.responsesUrl))
            .header("Accept", if (access.serverKeepsHistory) "application/json" else "text/event-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    return kotlinx.coroutines.withContext(ioDispatcher) {
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            try {
                val result =
                    call.execute().use {
                        if (!it.isSuccessful) error(openAiErrorMessage(it.code, it.body.string(), "the request"))
                        if (access.serverKeepsHistory) Json.parseToJsonElement(it.body.string()).jsonObject else collectResponsesStream(it)
                    }
                if (continuation.isActive) continuation.resume(result)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    }
}

internal fun collectResponsesStream(response: Response): JsonObject {
    val items = mutableListOf<JsonElement>()
    var id: String? = null
    var status = "completed"
    val source = response.body.source()
    while (true) {
        val line = source.readUtf8Line() ?: break
        if (!line.startsWith("data:")) continue
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty() || data == "[DONE]") continue
        val event = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
        when (val type = event.str("type")) {
            "response.output_item.done" -> {
                event["item"]?.let { items += it }
            }

            "error" -> {
                error(streamError(event.obj("error")))
            }

            else -> {
                if (type?.startsWith("response.") == true) {
                    val body = event.obj("response") ?: continue
                    id = body.str("id") ?: id
                    body.str("status")?.let { status = it }
                    body.obj("error")?.let { error(streamError(it)) }
                }
            }
        }
    }
    check(id != null && status == "completed") { "Incomplete Responses stream." }
    return buildJsonObject {
        put("id", id)
        put("status", status)
        put("output", JsonArray(items))
    }
}

private fun streamError(error: JsonObject?) =
    "OpenAI rejected the request: ${error?.str("message")?.trim()?.take(300) ?: "no reason was given"}"
