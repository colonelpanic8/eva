package com.colonelpanic.eva.adapters.android

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The bounded JSON both sides of the Shizuku helper boundary agree on. Requests name an operation
 * and the element EVA already observed; they never carry a command, component, or free-form target.
 */
object DeviceControlProtocol {
    const val TAP = "tap"
    const val SET_TEXT = "set_text"

    /** Why the helper refused before delivering input; each maps to a distinct user-facing message. */
    const val REASON_PACKAGE = "package"
    const val REASON_CHANGED = "changed"
    const val REASON_HIDDEN = "hidden"
    const val REASON_MISSING = "missing"
    const val REASON_REJECTED = "rejected"

    /** The helper failed before it delivered anything; every throwing path precedes injection. */
    const val REASON_ERROR = "error"

    /** Bounds may drift slightly between capture and input without meaning a different element. */
    const val BOUNDS_TOLERANCE_PX = 12

    fun request(
        operation: String,
        observation: UiObservation,
        node: UiNode,
        text: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("op", operation)
            put("package", observation.packageName)
            put("nodeIndex", node.index)
            put(
                "expect",
                buildJsonObject {
                    put("cls", node.className)
                    put("label", node.label)
                    put("l", node.left)
                    put("t", node.top)
                    put("r", node.right)
                    put("b", node.bottom)
                },
            )
            if (text != null) put("text", text)
        }

    fun failureMessage(
        reason: String,
        detail: String,
    ): String {
        val base =
            when (reason) {
                REASON_PACKAGE -> "The screen changed to a different app before EVA could act. Look at the screen again."
                REASON_CHANGED -> "That element moved or changed since EVA looked. Look at the screen again."
                REASON_HIDDEN -> "That element is no longer visible on screen. Look at the screen again."
                REASON_MISSING -> "That element is no longer on the screen. Look at the screen again."
                REASON_REJECTED -> "Android refused the input for that element."
                else -> return detail.ifBlank { "The device control helper could not complete the action." }
            }
        return if (detail.isBlank()) base else "$base ($detail)"
    }

    fun reasonOf(payload: JsonObject) = payload["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun detailOf(payload: JsonObject) = payload["detail"]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun observationOf(payload: JsonObject) = payload["observation"]?.jsonObject
}
