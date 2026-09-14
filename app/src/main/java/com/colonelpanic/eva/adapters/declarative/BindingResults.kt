package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.BoundedJson
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class HttpResponse(
    val status: Int,
    val body: String,
)

data class ContentRows(
    val rows: List<JsonObject>,
    val truncated: Boolean,
)

object BindingResults {
    fun http(
        capability: PackageCapability,
        response: HttpResponse,
    ): ExecutionOutcome {
        val binding = capability.binding as DeclarativeBinding.Http
        val read = capability.effect == PackageEffect.READ
        if (response.status ==
            202
        ) {
            return ExecutionOutcome(
                InvocationStatus.UNKNOWN,
                "The server accepted the request but did not establish completion. Check its status before retrying.",
            )
        }
        if (response.status !in 200..299) {
            return ExecutionOutcome(
                if (read) InvocationStatus.FAILED else InvocationStatus.UNKNOWN,
                "The server returned HTTP ${response.status}." + if (read) "" else " The write outcome is unknown; check before retrying.",
            )
        }
        val root = BoundedJson.parse(response.body, binding.maxResponseBytes)
        val result =
            pointer(root, binding.result.pointer) ?: return ExecutionOutcome(
                if (read) InvocationStatus.FAILED else InvocationStatus.UNKNOWN,
                "The response did not contain the declared result field.",
            )
        val confirmed = read || binding.result.evidence?.let { pointer(root, it.pointer) == it.expected } == true
        val status = if (confirmed) InvocationStatus.COMPLETED else InvocationStatus.UNKNOWN
        val projection = boundedText(result, binding.result.maxBytes)
        return ExecutionOutcome(status, (if (confirmed) "" else "The response did not establish completion of the write. ") + projection)
    }

    fun content(
        binding: DeclarativeBinding.Content,
        result: ContentRows,
    ): ExecutionOutcome {
        val rows =
            result.rows.take(binding.maxRows).map { row ->
                JsonObject(
                    binding.projection.mapValues { (column, type) ->
                        val value = row[column] ?: JsonNull
                        require(value == JsonNull || ToolSchema.error(JsonObject(mapOf("type" to JsonPrimitive(type))), value) == null) {
                            "Content column type did not match the approved projection"
                        }
                        value
                    },
                )
            }
        val text = boundedText(JsonArray(rows), binding.maxBytes)
        val truncated = result.truncated || result.rows.size > binding.maxRows
        return ExecutionOutcome(InvocationStatus.COMPLETED, (if (truncated) "Rows truncated. " else "") + text)
    }

    private fun pointer(
        root: JsonElement,
        path: String,
    ): JsonElement? {
        if (path.isEmpty()) return root
        var current: JsonElement = root
        for (part in path.substring(1).split('/')) {
            val key = part.replace("~1", "/").replace("~0", "~")
            current = when (val value = current) {
                is JsonObject -> value[key]
                is JsonArray -> key.takeIf { it == "0" || Regex("[1-9][0-9]*").matches(it) }?.toIntOrNull()?.let(value::getOrNull)
                else -> null
            } ?: return null
        }
        return current
    }

    private fun boundedText(
        value: JsonElement,
        maxBytes: Int,
    ): String {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: value.toString()
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text
        // Never split a UTF-8 code point; the annotation is EVA-owned receipt metadata.
        var end = maxBytes
        while (end > 0 && bytes[end].toInt() and 0xC0 == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8) + "\n[Result truncated]"
    }
}
