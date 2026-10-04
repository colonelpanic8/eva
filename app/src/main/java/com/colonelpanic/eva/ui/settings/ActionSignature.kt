package com.colonelpanic.eva.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One property of an action's input or output, flattened from EVA's JSON Schema subset for display. */
internal data class SchemaField(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String?,
    val constraints: List<String>,
    val children: List<SchemaField> = emptyList(),
)

/** The properties of an object schema in declared order; anything else has none. */
internal fun schemaFields(schema: JsonObject?): List<SchemaField> {
    val properties = schema?.get("properties") as? JsonObject ?: return emptyList()
    val required = (schema["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
    return properties.mapNotNull { (name, value) ->
        val property = value as? JsonObject ?: return@mapNotNull null
        SchemaField(
            name = name,
            type = typeLabel(property),
            required = name in required,
            description = property.string("description"),
            constraints = constraints(property),
            children = nestedFields(property),
        )
    }
}

/** The fields of the objects a value holds, however deep in arrays and maps they sit. */
private fun nestedFields(schema: JsonObject): List<SchemaField> =
    schemaFields(schema) + listOfNotNull(schema.element("items"), schema.element("additionalProperties")).flatMap(::nestedFields)

/** A TypeScript-like name: `string`, `integer[]`, `map<string>`, `object`. */
internal fun typeLabel(schema: JsonObject): String =
    when (val type = schema.string("type")) {
        "array" -> schema.element("items")?.let { "${typeLabel(it)}[]" } ?: "array"
        "object" -> schema.element("additionalProperties")?.let { "map<${typeLabel(it)}>" } ?: "object"
        else -> type ?: "any"
    }

/** What a root schema says about itself rather than its fields, such as that it may hold others. */
internal fun schemaNotes(schema: JsonObject): List<String> = constraints(schema)

/** Bounds on the value itself, then on what its arrays and maps hold, prefixed by where they apply. */
private fun constraints(
    schema: JsonObject,
    scope: String = "",
): List<String> =
    listOfNotNull(
        schema.enumValues()?.let { "${scope}one of $it" },
        range(schema, "minimum", "maximum", "")?.let { scope + it },
        range(schema, "minLength", "maxLength", " characters")?.let { scope + it },
        range(schema, "minItems", "maxItems", " items")?.let { scope + it },
        schema.number("maxProperties")?.let { "${scope}up to $it keys" },
        "${scope}may hold other fields".takeIf { schema["additionalProperties"] == JsonPrimitive(true) },
    ) + schema.element("items")?.let { constraints(it, "${scope}each item ") }.orEmpty() +
        schema.element("additionalProperties")?.let { constraints(it, "${scope}each value ") }.orEmpty()

private fun JsonObject.element(key: String) = this[key] as? JsonObject

private fun range(
    schema: JsonObject,
    low: String,
    high: String,
    unit: String,
): String? {
    val min = schema.number(low)
    val max = schema.number(high)
    return when {
        min != null && max != null -> "$min–$max$unit"
        min != null -> "at least $min$unit"
        max != null -> "up to $max$unit"
        else -> null
    }
}

private fun JsonObject.enumValues(): String? =
    (this["enum"] as? JsonArray)?.joinToString(", ") { value ->
        (value as? JsonPrimitive)?.let { if (it.isString) "\"${it.content}\"" else it.content } ?: value.toString()
    }

private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.number(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless(JsonElement::isStringPrimitive)?.content?.removeSuffix(".0")

private val JsonElement.isStringPrimitive get() = this is JsonPrimitive && isString

/** What an action takes and, when it says, what it gives back. */
@Composable
internal fun ActionSignature(
    inputSchema: JsonObject,
    outputSchema: JsonObject?,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val inputs = schemaFields(inputSchema)
        SignatureHeading("Parameters")
        if (inputs.isEmpty()) {
            Text("None", style = MaterialTheme.typography.bodySmall)
        } else {
            inputs.forEach { FieldLine(it) }
        }
        if (outputSchema != null) {
            SignatureHeading("Returns")
            outputSchema.string("description")?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val notes = schemaNotes(outputSchema)
            if (notes.isNotEmpty()) {
                Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            val outputs = schemaFields(outputSchema)
            if (outputs.isEmpty() && notes.isEmpty()) Text("An empty object", style = MaterialTheme.typography.bodySmall)
            outputs.forEach { FieldLine(it) }
        }
    }
}

@Composable
private fun SignatureHeading(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun FieldLine(
    field: SchemaField,
    nested: Boolean = false,
) {
    Column(Modifier.padding(start = if (nested) 12.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) { append(field.name) }
                if (!field.required) append("?")
                append(": ")
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(field.type) }
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        if (field.constraints.isNotEmpty()) {
            Text(
                field.constraints.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        field.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        field.children.forEach { FieldLine(it, nested = true) }
    }
}
