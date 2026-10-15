package com.colonelpanic.eva.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The public Live API and Codex's subscription voice use different wire events. */
internal enum class LiveProtocol {
    API,
    CODEX,
    ;

    val historyField: String get() = if (this == CODEX) "initial_items" else "input"

    fun receive(event: JsonObject): JsonObject {
        if (this == API) return event
        val type = event.str("type")
        val item = event.obj("item")
        return when (type) {
            "input_transcript.added", "output_transcript.added", "delegation.created" -> {
                buildJsonObject {
                    event.forEach { (key, value) -> put(key, value) }
                    item?.str("id")?.let { if (event.str("event_id") == null) put("event_id", it) }
                    if (type == "delegation.created") {
                        put("type", "session.delegation.created")
                        put("delegation", requireNotNull(item) { "Codex Live omitted delegation metadata." })
                    } else {
                        put(
                            "type",
                            if (type ==
                                "input_transcript.added"
                            ) {
                                "session.input_transcript.delta"
                            } else {
                                "session.output_transcript.delta"
                            },
                        )
                        put("delta", requireNotNull(item?.str("text")) { "Codex Live caption omitted its text." })
                    }
                }
            }

            else -> {
                event
            }
        }
    }

    fun append(
        eventId: String,
        delegationId: String?,
        content: String,
        respond: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("event_id", eventId)
            when (this@LiveProtocol) {
                API -> {
                    put("type", if (respond) "session.commentary.append" else "session.thinking.append")
                    put("delegation_id", delegationId?.let(::JsonPrimitive) ?: JsonNull)
                    put("content", content)
                }

                CODEX -> {
                    put("type", if (delegationId == null) "session.context.append" else "delegation.context.append")
                    delegationId?.let { put("delegation_item_id", it) }
                    if (respond) put("channel", "speakable")
                    put(
                        "content",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("type", "input_text")
                                    put("text", content)
                                },
                            ),
                        ),
                    )
                }
            }
        }
}
