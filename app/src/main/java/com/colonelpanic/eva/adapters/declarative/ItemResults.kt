package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Whole lines only: identifiers are never partially emitted when the byte budget is exhausted. */
object ItemResults {
    fun render(
        root: JsonElement,
        projection: ItemProjection,
        maxBytes: Int,
    ): String {
        val arrays = projection.arrayPaths.firstNotNullOfOrNull { arrays(root, it) } ?: error("No declared result array was present")
        val returned = arrays.sumOf { it.size.toLong() }
        val total =
            projection.totalPointer?.let { path ->
                BindingResults.pointer(root, path)?.takeUnless { it == JsonNull }?.let {
                    val count = (it as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                    require(count != null && count >= 0) { "Invalid total count" }
                    count
                }
            }
        var truncated = (total ?: returned) > returned || returned > projection.maxItems
        val lines = mutableListOf<String>()
        var bytes = 0
        for (item in arrays.asSequence().flatMap { it.asSequence() }.take(projection.maxItems)) {
            require(item is JsonObject)
            val fields =
                projection.fields.mapValues { (_, field) ->
                    val value = BindingResults.pointer(item, field.pointer) ?: JsonNull
                    if (value == JsonNull) {
                        require(!field.required) { "Required item field missing" }
                    } else if (field.type == "stringArray") {
                        require(value is JsonArray && value.all { it is JsonPrimitive && it.isString })
                    } else {
                        require(ToolSchema.error(JsonObject(mapOf("type" to JsonPrimitive(field.type))), value) == null)
                    }
                    // JSON quoting preserves exact identifiers and prevents newlines from forging extra items.
                    value.toString()
                }
            val line = Regex("\\{([A-Za-z_][A-Za-z0-9_]{0,63})}").replace(projection.line) { fields.getValue(it.groupValues[1]) }
            val size = line.toByteArray(Charsets.UTF_8).size + if (lines.isEmpty()) 0 else 1
            if (bytes + size > maxBytes) {
                truncated = true
                break
            }
            lines += line
            bytes += size
        }
        val data = lines.joinToString("\n").ifEmpty { if (returned == 0L) "No items." else "No complete item fits the result budget." }
        return data + if (truncated) "\n[Truncated] ${projection.truncationNote}" else ""
    }

    private fun arrays(
        root: JsonElement,
        path: String,
    ): List<JsonArray>? {
        if (path.isEmpty()) return (root as? JsonArray)?.let(::listOf)
        var nodes = listOf(root)
        for (raw in path.substring(1).split('/')) {
            val key = raw.replace("~1", "/").replace("~0", "~")
            val next = mutableListOf<JsonElement>()
            for (node in nodes) {
                when {
                    key == "*" && node is JsonObject -> next.addAll(node.values)
                    key == "*" && node is JsonArray -> next.addAll(node)
                    node is JsonObject && key in node -> next += node.getValue(key)
                    else -> return null
                }
                require(next.size <= 4096) { "Result projection traversal limit exceeded" }
            }
            nodes = next
        }
        require(nodes.all { it is JsonArray }) { "Declared result path was not an array" }
        return nodes.map { it as JsonArray }
    }
}
