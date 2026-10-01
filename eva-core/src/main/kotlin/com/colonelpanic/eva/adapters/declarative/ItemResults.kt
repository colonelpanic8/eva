package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

data class ProjectedItems(
    val text: String,
    /** The same whole items as the text, keyed by slot name, with truncation and source total. */
    val data: JsonObject,
)

/** Whole lines only: identifiers are never partially emitted when the byte budget is exhausted. */
object ItemResults {
    private val SLOT = Regex("\\{([A-Za-z_][A-Za-z0-9_]{0,63})\\}")

    fun render(
        root: JsonElement,
        projection: ItemProjection,
        maxBytes: Int,
        arguments: Map<String, String> = emptyMap(),
    ): String = project(root, projection, maxBytes, arguments).text

    fun project(
        root: JsonElement,
        projection: ItemProjection,
        maxBytes: Int,
        arguments: Map<String, String> = emptyMap(),
    ): ProjectedItems {
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
        val query = projection.filter?.let { arguments[it.argument] }
        val matches =
            arrays
                .asSequence()
                .flatMap { it.asSequence() }
                .filter { item ->
                    query == null ||
                        checkNotNull(projection.filter).fields.any { path ->
                            val text = BindingResults.pointer(item, path) as? JsonPrimitive
                            text?.isString == true && text.content.contains(query, ignoreCase = true)
                        }
                }.take(projection.maxItems + 1)
                .toList()
        val sourceTruncated = (total ?: returned) > returned
        var truncated = sourceTruncated || matches.size > projection.maxItems
        val lines = mutableListOf<String>()
        val records = mutableListOf<JsonObject>()
        var bytes = 0
        for (item in matches.take(projection.maxItems)) {
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
                    value
                }
            // JSON quoting preserves exact identifiers and prevents newlines from forging extra items.
            val line = SLOT.replace(projection.line) { fields.getValue(it.groupValues[1]).toString() }
            val size = line.toByteArray(Charsets.UTF_8).size + if (lines.isEmpty()) 0 else 1
            if (bytes + size > maxBytes) {
                truncated = true
                break
            }
            lines += line
            records += JsonObject(fields)
            bytes += size
        }
        val data =
            lines.joinToString("\n").ifEmpty {
                when {
                    matches.isEmpty() && query != null -> "No matching items in the returned data."
                    matches.isEmpty() -> "No items."
                    else -> "No complete item fits the result budget."
                }
            }
        val structured =
            JsonObject(
                buildMap {
                    put("items", JsonArray(records))
                    put("truncated", JsonPrimitive(truncated))
                    put("sourceTruncated", JsonPrimitive(sourceTruncated))
                    total?.let { put("total", JsonPrimitive(it)) }
                },
            )
        val text =
            data +
                if (truncated) {
                    "\n[Truncated] " +
                        (
                            if (sourceTruncated &&
                                query != null
                            ) {
                                "The source returned only part of its data; additional matches may exist. "
                            } else {
                                ""
                            }
                        ) +
                        projection.truncationNote
                } else {
                    ""
                }
        return ProjectedItems(text, structured)
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
