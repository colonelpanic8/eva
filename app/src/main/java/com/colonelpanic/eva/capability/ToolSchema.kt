package com.colonelpanic.eva.capability

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

object ToolSchema {
    fun check(
        schema: JsonObject,
        depth: Int = 0,
    ) {
        require(depth <= 8) { "Schema nesting exceeds eight levels" }
        val type = (schema["type"] as? JsonPrimitive)?.content
        val allowed =
            setOf("type", "description", "enum") +
                when (type) {
                    "object" -> setOf("properties", "required", "additionalProperties")
                    "string" -> setOf("minLength", "maxLength")
                    "integer", "number" -> setOf("minimum", "maximum")
                    "boolean" -> emptySet()
                    else -> error("Unsupported schema type: $type")
                }
        require(schema.keys.all { it in allowed }) { "Unsupported schema keyword" }
        schema["description"]?.let { require(it is JsonPrimitive && it.isString) }
        schema["enum"]?.let { values ->
            require(values is JsonArray && values.size in 1..64 && type != "object")
            val withoutEnum = JsonObject(schema - "enum")
            require(values.all { error(withoutEnum, it, depth) == null }) { "Invalid enum value" }
        }
        if (type == "object") {
            require(schema["additionalProperties"] == JsonPrimitive(false)) { "Objects must be closed" }
            val properties = schema["properties"] as? JsonObject ?: error("Missing properties")
            require(properties.size <= 64)
            val required = schema["required"] as? JsonArray ?: error("Missing required fields")
            require(required.all { it is JsonPrimitive && it.isString && it.content in properties })
            require(required.distinct().size == required.size)
            properties.values.forEach { check(it as? JsonObject ?: error("Invalid property schema"), depth + 1) }
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
                    val properties = schema["properties"] as? JsonObject ?: return "Invalid schema."
                    val required = schema["required"] as? JsonArray ?: return "Invalid schema."
                    value is JsonObject && value.keys.all { it in properties } &&
                        required.all { (it as JsonPrimitive).content in value } &&
                        value.all { (key, child) -> error(properties.getValue(key) as JsonObject, child, depth + 1) == null }
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
