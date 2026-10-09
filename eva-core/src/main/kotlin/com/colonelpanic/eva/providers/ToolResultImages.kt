package com.colonelpanic.eva.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/** Images stay with the journaled receipt; text serialization never expands their base64 data. */
data class ToolResultImage(
    val mimeType: String,
    val data: String,
) {
    init {
        require(mimeType in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) { "Unsupported tool image type: $mimeType" }
        require(data.isNotEmpty()) { "The tool returned an empty image." }
        Base64.getDecoder().decode(data)
    }

    fun toJson() =
        buildJsonObject {
            put("mimeType", mimeType)
            put("data", data)
        }

    fun openAiContent() =
        buildJsonObject {
            put("type", "input_image")
            put("image_url", "data:$mimeType;base64,$data")
            put("detail", "auto")
        }
}

private const val IMAGE_FIELD = "_evaToolImages"

fun JsonObject.withToolImages(images: List<ToolResultImage>): JsonObject =
    if (images.isEmpty()) this else JsonObject(this + (IMAGE_FIELD to JsonArray(images.map { it.toJson() })))

fun JsonObject.withoutToolImages(): JsonObject = JsonObject(this - IMAGE_FIELD)

fun JsonObject.toolImages(): List<ToolResultImage> =
    (this[IMAGE_FIELD] as? JsonArray)
        ?.mapNotNull { element ->
            val image = element as? JsonObject ?: return@mapNotNull null
            val mime = (image["mimeType"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val data = (image["data"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            runCatching { ToolResultImage(mime, data) }.getOrNull()
        }.orEmpty()

internal fun CorrelatedToolResult.openAiOutput(): JsonElement {
    val text = wireOutcome().toString()
    val images = data?.toolImages().orEmpty()
    return if (images.isEmpty()) {
        JsonPrimitive(text)
    } else {
        JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "input_text")
                    put("text", text)
                },
            ) + images.map { it.openAiContent() },
        )
    }
}
