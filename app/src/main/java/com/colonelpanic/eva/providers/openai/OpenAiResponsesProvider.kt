package com.colonelpanic.eva.providers.openai

import com.colonelpanic.eva.capability.ToolSchema
import com.colonelpanic.eva.providers.CallIdentity
import com.colonelpanic.eva.providers.ConversationInput
import com.colonelpanic.eva.providers.ConversationProvider
import com.colonelpanic.eva.providers.ConversationSession
import com.colonelpanic.eva.providers.CorrelatedToolResult
import com.colonelpanic.eva.providers.ProviderEvent
import com.colonelpanic.eva.providers.ProviderToolDefinition
import com.colonelpanic.eva.providers.ResponseRequest
import com.colonelpanic.eva.providers.SessionOpenRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.UUID

/** Typed turns straight from the phone over the OpenAI Responses API, with function calling. */
class OpenAiResponsesProvider(
    private val access: OpenAiAccess,
    private val model: String = OpenAiModels.TEXT,
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val catalog: OpenAiModelCatalog = OpenAiModelCatalog(client, ioDispatcher),
) : ConversationProvider {
    /**
     * Unlike a realtime session, a typed session has nothing to negotiate, so "connected"
     * would otherwise mean only that a screen changed. Listing the account's models proves
     * the credentials work and the chosen model exists before anything is claimed.
     */
    override suspend fun open(request: SessionOpenRequest): ConversationSession {
        require(request.catalog.tools.size <= 32)
        request.catalog.tools.forEach { ToolSchema.check(it.inputSchema) }
        val known = catalog.load(access).values.flatten()
        check(known.isEmpty() || model in known) {
            "This account cannot use $model. Choose another text model."
        }
        return OpenAiResponsesSession(access, model, client, request, toolNames(request.catalog.tools), ioDispatcher)
    }
}

private sealed interface Command {
    data class Respond(
        val input: ConversationInput,
    ) : Command

    data class ToolResult(
        val result: CorrelatedToolResult,
    ) : Command
}

private class OpenAiResponsesSession(
    private val access: OpenAiAccess,
    private val model: String,
    private val client: OkHttpClient,
    private val request: SessionOpenRequest,
    private val tools: Map<String, ProviderToolDefinition>,
    private val ioDispatcher: CoroutineDispatcher,
) : ConversationSession {
    override val connectionEpoch: String = UUID.randomUUID().toString()
    private val sessionId = UUID.randomUUID().toString()
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var buffered: ConversationInput? = null
    private var previousResponseId: String? = null

    /** Only needed where the provider keeps nothing: the phone then resends the conversation. */
    private val history = mutableListOf<JsonElement>()
    private val pending = mutableMapOf<String, CallIdentity>()
    private val json = Json { ignoreUnknownKeys = true }

    override val events: Flow<ProviderEvent> =
        flow {
            emit(ProviderEvent.Account(access.label))
            emit(ProviderEvent.Connected(sessionId, request.catalog.revision, model))
            for (command in commands) {
                val input = (command as? Command.Respond)?.input ?: continue
                emit(ProviderEvent.ResponseStarted(input.id, input.id))
                try {
                    var body = post(JsonArray(listOf(userMessage(input.text))))
                    while (true) {
                        val responseId = body.str("id") ?: error("The provider returned no response ID.")
                        previousResponseId = responseId
                        val output = body["output"]?.jsonArray.orEmpty()
                        val calls = output.mapNotNull { it as? JsonObject }.filter { it.str("type") == "function_call" }
                        if (calls.isEmpty()) {
                            val text =
                                output
                                    .mapNotNull { it as? JsonObject }
                                    .filter { it.str("type") == "message" }
                                    .flatMap { it["content"]?.jsonArray.orEmpty() }
                                    .mapNotNull { (it as? JsonObject)?.takeIf { part -> part.str("type") == "output_text" }?.str("text") }
                                    .joinToString("\n")
                            if (text.isNotBlank()) emit(ProviderEvent.AssistantText(input.id, text, false))
                            emit(ProviderEvent.ResponseEnded(input.id, body.str("status") ?: "completed"))
                            break
                        }
                        for (call in calls) {
                            val callId = call.str("call_id") ?: continue
                            val tool = tools[call.str("name")] ?: error("The model called a tool that was not advertised.")
                            val arguments =
                                runCatching { json.parseToJsonElement(call.str("arguments").orEmpty()).jsonObject }
                                    .getOrDefault(JsonObject(emptyMap()))
                            val identity =
                                CallIdentity(connectionEpoch, sessionId, input.id, input.id, responseId, request.catalog.revision, callId)
                            pending[callId] = identity
                            emit(ProviderEvent.ToolCallReady(identity, tool.capabilityId, arguments))
                        }
                        val outputs = mutableListOf<JsonElement>()
                        while (pending.isNotEmpty()) {
                            val next = commands.receive()
                            val result =
                                (next as? Command.ToolResult)?.result ?: error("A new request arrived while a tool call was pending.")
                            check(pending.remove(result.call.callId) == result.call)
                            outputs +=
                                buildJsonObject {
                                    put("type", "function_call_output")
                                    put("call_id", result.call.callId)
                                    put(
                                        "output",
                                        buildJsonObject {
                                            put("status", result.status)
                                            put("message", result.message.take(2000))
                                            result.data?.let { put("data", it) }
                                        }.toString(),
                                    )
                                }
                        }
                        body = post(JsonArray(outputs))
                    }
                } catch (error: IllegalStateException) {
                    emit(ProviderEvent.Failure(error.message ?: "The provider request failed."))
                    break
                }
            }
            emit(ProviderEvent.Closed)
        }

    /**
     * The stored path shipped with the plain content form; the subscription backend is
     * exercised with the explicit item form, so each keeps the shape it was proven against.
     */
    private fun userMessage(text: String): JsonObject =
        if (access.serverKeepsHistory) {
            buildJsonObject {
                put("role", "user")
                put("content", text)
            }
        } else {
            buildJsonObject {
                put("type", "message")
                put("role", "user")
                put(
                    "content",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("type", "input_text")
                                put("text", text)
                            },
                        ),
                    ),
                )
            }
        }

    private suspend fun post(input: JsonArray): JsonObject =
        withContext(ioDispatcher) {
            val payload =
                buildJsonObject {
                    put("model", model)
                    put("instructions", request.instructions)
                    put("input", if (access.serverKeepsHistory) input else JsonArray(history + input))
                    put("store", access.serverKeepsHistory)
                    put("stream", !access.serverKeepsHistory)
                    if (access.serverKeepsHistory) previousResponseId?.let { put("previous_response_id", it) }
                    if (tools.isNotEmpty()) {
                        put("tools", functionTools(tools, strict = false))
                        put("tool_choice", "auto")
                    }
                }
            val http =
                access
                    .authorize(Request.Builder().url(access.responsesUrl))
                    .header("Accept", if (access.serverKeepsHistory) "application/json" else "text/event-stream")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            val body =
                client.newCall(http).execute().use { response ->
                    if (!response.isSuccessful) {
                        error(openAiErrorMessage(response.code, response.body.string(), "the request"))
                    }
                    if (access.serverKeepsHistory) {
                        json.parseToJsonElement(response.body.string()).jsonObject
                    } else {
                        collectStream(response)
                    }
                }
            if (!access.serverKeepsHistory) {
                history.addAll(input)
                history.addAll(body["output"]?.jsonArray.orEmpty())
            }
            body
        }

    /**
     * A streamed response delivers its items one event at a time and leaves the completion
     * event's own output empty, so the items are collected into the same shape the stored
     * path returns and the rest of the session cannot tell the two apart.
     */
    private fun collectStream(response: Response): JsonObject {
        val items = mutableListOf<JsonElement>()
        var id: String? = null
        var status = "completed"
        val source = response.body.source()
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val event = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
            when (val type = event.str("type")) {
                "response.output_item.done" -> {
                    event["item"]?.let { items += it }
                }

                "error" -> {
                    error(streamFailure(event.obj("error")))
                }

                else -> {
                    if (type != null && type.startsWith("response.")) {
                        val body = event.obj("response") ?: continue
                        id = body.str("id") ?: id
                        body.str("status")?.let { status = it }
                        body.obj("error")?.let { error(streamFailure(it)) }
                    }
                }
            }
        }
        return buildJsonObject {
            id?.let { put("id", it) }
            put("status", status)
            put("output", JsonArray(items))
        }
    }

    private fun streamFailure(error: JsonObject?): String =
        "OpenAI rejected the request: ${error?.str("message")?.trim()?.take(300) ?: "no reason was given"}"

    override suspend fun submit(input: ConversationInput) {
        check(buffered == null)
        require(input.text.isNotBlank() && input.text.length <= 4000)
        buffered = input
    }

    override suspend fun requestResponse(request: ResponseRequest) {
        val input = checkNotNull(buffered)
        check(input.id == request.inputId)
        buffered = null
        commands.send(Command.Respond(input))
    }

    override suspend fun submitToolResult(result: CorrelatedToolResult) {
        check(result.call.connectionEpoch == connectionEpoch)
        commands.send(Command.ToolResult(result))
    }

    override suspend fun close() {
        commands.close()
    }
}
