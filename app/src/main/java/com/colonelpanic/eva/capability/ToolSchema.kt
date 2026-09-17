package com.colonelpanic.eva.capability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * EVA's JSON Schema subset. Input schemas are closed objects whose properties are scalars or
 * arrays of scalars; output schemas may additionally nest objects and arrays and stay open.
 */
object ToolSchema {
    const val MAX_INPUT_ITEMS = 64
    const val MAX_OUTPUT_ITEMS = 4096
    val scalarTypes = setOf("string", "integer", "number", "boolean")

    /**
     * Rebuilds a JSON object from a backend's flat string arguments, restoring the
     * type each property declares. Arrays travel as JSON text. A value that does not
     * parse stays a string so schema validation rejects it.
     */
    fun coerce(
        schema: JsonObject,
        arguments: Map<String, String>,
    ): JsonObject {
        val properties = schema["properties"] as? JsonObject
        return JsonObject(
            arguments.mapValues { (key, value) ->
                val declared = ((properties?.get(key) as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
                when (declared) {
                    "integer" -> value.toLongOrNull()?.let { JsonPrimitive(it) }
                    "number" -> value.toDoubleOrNull()?.let { JsonPrimitive(it) }
                    "boolean" -> value.toBooleanStrictOrNull()?.let { JsonPrimitive(it) }
                    "array" -> runCatching { Json.parseToJsonElement(value) as? JsonArray }.getOrNull()
                    "object" -> runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull()
                    else -> null
                } ?: JsonPrimitive(value)
            },
        )
    }

    fun check(
        schema: JsonObject,
        depth: Int = 0,
        output: Boolean = false,
    ) {
        require(depth <= 8) { "Schema nesting exceeds eight levels" }
        val type = (schema["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val allowed =
            setOf("type", "description", "enum") +
                when (type) {
                    "object" -> setOf("properties", "required", "additionalProperties", "maxProperties")
                    "array" -> setOf("items", "minItems", "maxItems")
                    "string" -> setOf("minLength", "maxLength")
                    "integer", "number" -> setOf("minimum", "maximum")
                    "boolean" -> emptySet()
                    else -> error("Unsupported schema type: $type")
                }
        require(schema.keys.all { it in allowed }) { "Unsupported schema keyword" }
        schema["description"]?.let { require(it is JsonPrimitive && it.isString) }
        schema["enum"]?.let { values ->
            require(values is JsonArray && values.size in 1..64 && type != "object" && type != "array")
            val withoutEnum = JsonObject(schema - "enum")
            require(values.all { error(withoutEnum, it, depth) == null }) { "Invalid enum value" }
        }
        if (type == "object" && schema["additionalProperties"] is JsonObject) {
            // A string map: the keys are the request's, bounded in count; only the value shape is declared.
            require(depth > 0 && "properties" !in schema && "required" !in schema) { "A map object declares only its values" }
            val values = schema.getValue("additionalProperties") as JsonObject
            require(output || values["type"] == JsonPrimitive("string")) { "Input maps hold strings" }
            check(values, depth + 1, output)
            val cap = (schema["maxProperties"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            require(cap != null && cap in 1..MAX_INPUT_ITEMS) { "Maps declare maxProperties" }
        } else if (type == "object") {
            require("maxProperties" !in schema) { "maxProperties belongs to map objects" }
            val open = (schema["additionalProperties"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            require(open == false || (output && open == true)) { "Objects must be closed" }
            val properties = schema["properties"] as? JsonObject ?: error("Missing properties")
            require(properties.size <= 64)
            val required = schema["required"] as? JsonArray ?: error("Missing required fields")
            require(required.all { it is JsonPrimitive && it.isString && it.content in properties })
            require(required.distinct().size == required.size)
            properties.values.forEach { check(it as? JsonObject ?: error("Invalid property schema"), depth + 1, output) }
        }
        if (type == "array") {
            val items = schema["items"] as? JsonObject ?: error("Arrays declare an items schema")
            val itemType = (items["type"] as? JsonPrimitive)?.contentOrNull
            require(output || itemType in scalarTypes) { "Input arrays hold scalars" }
            check(items, depth + 1, output)
            val ceiling = if (output) MAX_OUTPUT_ITEMS else MAX_INPUT_ITEMS
            for (name in listOf("minItems", "maxItems")) {
                schema[name]?.let {
                    require(it is JsonPrimitive && !it.isString && it.intOrNull != null && it.intOrNull!! in 0..ceiling)
                }
            }
            require(bound(schema, "minItems", 0.0) <= bound(schema, "maxItems", ceiling.toDouble()))
        }
        if (type == "string") {
            for (name in listOf("minLength", "maxLength")) {
                schema[name]?.let {
                    require(it is JsonPrimitive && !it.isString && it.intOrNull != null && it.intOrNull!! in 0..65536)
                }
            }
            require(bound(schema, "minLength", 0.0) <= bound(schema, "maxLength", 65536.0))
        }
        if (type == "integer" || type == "number") {
            for (name in listOf("minimum", "maximum")) {
                schema[name]?.let {
                    require(it is JsonPrimitive && !it.isString && it.doubleOrNull?.isFinite() == true)
                }
            }
            require(bound(schema, "minimum", -Double.MAX_VALUE) <= bound(schema, "maximum", Double.MAX_VALUE))
        }
    }

    fun error(
        schema: JsonObject,
        value: JsonElement,
        depth: Int = 0,
    ): String? {
        if (depth > 8 || value == JsonNull) return "Invalid or excessively nested arguments."
        val primitive = value as? JsonPrimitive
        val valid =
            when ((schema["type"] as? JsonPrimitive)?.contentOrNull) {
                "object" -> {
                    val values = schema["additionalProperties"] as? JsonObject
                    if (values != null) {
                        value is JsonObject &&
                            value.size.toDouble() <= bound(schema, "maxProperties", MAX_INPUT_ITEMS.toDouble()) &&
                            value.all { (key, child) ->
                                key.length in 1..64 && key.none { it.isISOControl() } && error(values, child, depth + 1) == null
                            }
                    } else {
                        val properties = schema["properties"] as? JsonObject ?: return "Invalid schema."
                        val required = schema["required"] as? JsonArray ?: return "Invalid schema."
                        val open = schema["additionalProperties"] == JsonPrimitive(true)
                        value is JsonObject && (open || value.keys.all { it in properties }) &&
                            required.all { (it as JsonPrimitive).content in value } &&
                            value.all { (key, child) ->
                                val property = properties[key] as? JsonObject
                                property == null || error(property, child, depth + 1) == null
                            }
                    }
                }

                "array" -> {
                    val items = schema["items"] as? JsonObject ?: return "Invalid schema."
                    value is JsonArray &&
                        value.size.toDouble() in bound(schema, "minItems", 0.0)..bound(schema, "maxItems", MAX_OUTPUT_ITEMS.toDouble()) &&
                        value.all { error(items, it, depth + 1) == null }
                }

                "string" -> {
                    primitive?.isString == true &&
                        primitive.content.codePointCount(0, primitive.content.length).toDouble() in
                        bound(schema, "minLength", 0.0)..bound(schema, "maxLength", Double.MAX_VALUE)
                }

                "boolean" -> {
                    primitive?.isString == false && primitive.booleanOrNull != null
                }

                "integer", "number" -> {
                    val number = primitive?.takeUnless { it.isString }?.doubleOrNull
                    number != null && number.isFinite() &&
                        number in bound(schema, "minimum", -Double.MAX_VALUE)..bound(schema, "maximum", Double.MAX_VALUE) &&
                        (schema["type"] != JsonPrimitive("integer") || number % 1.0 == 0.0)
                }

                else -> {
                    false
                }
            }
        if (!valid) return "Arguments do not match this action's schema."
        val values = schema["enum"] as? JsonArray
        return if (values != null && value !in values) "Argument is not an allowed value." else null
    }

    private fun bound(
        schema: JsonObject,
        name: String,
        fallback: Double,
    ) = (schema[name] as? JsonPrimitive)?.doubleOrNull ?: fallback
}
