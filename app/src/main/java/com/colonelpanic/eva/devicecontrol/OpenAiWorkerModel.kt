package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.devicecontrol.worker.WorkerCall
import com.colonelpanic.eva.devicecontrol.worker.WorkerModel
import com.colonelpanic.eva.devicecontrol.worker.WorkerReply
import com.colonelpanic.eva.devicecontrol.worker.WorkerRequest
import com.colonelpanic.eva.providers.openai.OpenAiAccess
import com.colonelpanic.eva.providers.openai.responsesPost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/** Uses the same subscription authorization and HTTP/SSE transport as EVA's text provider. */
class OpenAiWorkerModel(
    private val access: OpenAiAccess,
    private val model: String = "gpt-6-sol",
    private val effort: String = "low",
    private val client: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
) : WorkerModel {
    override suspend fun complete(request: WorkerRequest): WorkerReply {
        val body =
            responsesPost(
                client,
                access,
                buildJsonObject {
                    put("model", model)
                    put("instructions", request.instructions)
                    put("reasoning", buildJsonObject { put("effort", effort) })
                    put("store", false)
                    put("stream", !access.serverKeepsHistory)
                    put("parallel_tool_calls", false)
                    put("tool_choice", "required")
                    put("prompt_cache_key", request.cacheKey)
                    put(
                        "input",
                        JsonArray(
                            request.messages.map { message ->
                                buildJsonObject {
                                    put("role", message.role)
                                    put(
                                        "content",
                                        JsonArray(
                                            buildList {
                                                add(
                                                    buildJsonObject {
                                                        put(
                                                            "type",
                                                            if (message.role ==
                                                                "assistant"
                                                            ) {
                                                                "output_text"
                                                            } else {
                                                                "input_text"
                                                            },
                                                        )
                                                        ; put("text", message.text)
                                                    },
                                                )
                                                message.png?.let {
                                                    add(
                                                        buildJsonObject {
                                                            put("type", "input_image")
                                                            put("image_url", "data:image/png;base64,$it")
                                                        },
                                                    )
                                                }
                                            },
                                        ),
                                    )
                                }
                            },
                        ),
                    )
                    put(
                        "tools",
                        JsonArray(
                            request.tools.map { tool ->
                                buildJsonObject {
                                    put("type", "function")
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", tool.parameters)
                                    put("strict", true)
                                }
                            },
                        ),
                    )
                },
            )
        val calls =
            body["output"]
                ?.jsonArray
                .orEmpty()
                .mapNotNull { it as? JsonObject }
                .filter {
                    it["type"]?.jsonPrimitive?.content ==
                        "function_call"
                }.map {
                    WorkerCall(
                        it.getValue("call_id").jsonPrimitive.content,
                        it.getValue("name").jsonPrimitive.content,
                        Json.parseToJsonElement(it.getValue("arguments").jsonPrimitive.content).jsonObject,
                    )
                }
        return WorkerReply(calls)
    }
}
