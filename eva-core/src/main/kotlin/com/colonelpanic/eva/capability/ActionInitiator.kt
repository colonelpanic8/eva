package com.colonelpanic.eva.capability

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

enum class InitiatorKind(
    val wireName: String,
    val label: String,
) {
    USER_SPEECH("user_speech", "user speech"),
    USER_TYPED("user_typed", "typed request"),
    TEXT_AGENT("text_agent", "text agent"),
    DEVICE_TASK_WORKER("device_task_worker", "device task worker"),
    LIFECYCLE_NOTE_REPLY("lifecycle_note_reply", "background announcement"),
    UNKNOWN("unknown", "unknown origin"),
}

/** Host-authored initiation identity, separate from the external content's receipt provenance. */
data class ActionInitiator(
    val kind: InitiatorKind,
    val inputId: String? = null,
    val responseId: String? = null,
    val itemId: String? = null,
    val legId: String? = null,
    val parentResponseId: String? = null,
    val outputItemId: String? = null,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("kind", kind.wireName)
            inputId?.let { put("inputId", it) }
            responseId?.let { put("responseId", it) }
            itemId?.let { put("itemId", it) }
            legId?.let { put("legId", it) }
            parentResponseId?.let { put("parentResponseId", it) }
            outputItemId?.let { put("outputItemId", it) }
        }

    companion object {
        fun fromJson(value: JsonObject): ActionInitiator {
            fun text(key: String) = (value[key] as? JsonPrimitive)?.contentOrNull
            return ActionInitiator(
                InitiatorKind.entries.firstOrNull { it.wireName == text("kind") } ?: InitiatorKind.UNKNOWN,
                text("inputId"),
                text("responseId"),
                text("itemId"),
                text("legId"),
                text("parentResponseId"),
                text("outputItemId"),
            )
        }
    }
}
