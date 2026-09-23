package com.colonelpanic.eva.conversation.prompt

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import com.colonelpanic.eva.providers.ProviderToolDefinition
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the model reads about one of EVA's own tools. */
@Serializable
data class ToolText(
    val description: String? = null,
    /** Parameter name to description; a parameter the schema does not declare is ignored. */
    val parameters: Map<String, String> = emptyMap(),
)

/**
 * The model-facing wording of EVA's own tools and notes, kept as data like the stock prompt:
 * `eva-wording.yaml` in the instruction catalog, shipped byte-identical as a resource and
 * followed alongside the prompt. Code keeps tool identity, schema structure, and validation;
 * this file only says what they are for.
 */
@Serializable
data class Wording(
    val tools: Map<String, ToolText> = emptyMap(),
    val messages: Map<String, String> = emptyMap(),
) {
    /** A followed file missing a note falls back to the shipped one. */
    fun message(key: String): String = messages[key] ?: bundled.messages.getValue(key)

    fun describe(tool: ProviderToolDefinition): ProviderToolDefinition {
        val text = tools[tool.capabilityId] ?: return tool
        return tool.copy(
            description = text.description ?: tool.description,
            inputSchema = withParameters(tool.inputSchema, text.parameters),
        )
    }

    private fun withTrimmedText() =
        copy(
            tools =
                tools.mapValues { (_, text) ->
                    text.copy(description = text.description?.trimEnd(), parameters = text.parameters.mapValues { it.value.trimEnd() })
                },
            messages = messages.mapValues { it.value.trimEnd() },
        )

    companion object {
        const val FILE_NAME = "eva-wording.yaml"
        const val CONTINUATION = "continuation"
        const val HANG_UP_DEFERRED = "hang-up-deferred"

        private val yaml = Yaml(configuration = YamlConfiguration(encodeDefaults = false))

        val bundled: Wording by lazy {
            val text =
                checkNotNull(
                    Wording::class.java.getResourceAsStream("/$FILE_NAME"),
                ) { "The stock wording is missing." }.use { it.readBytes() }
            decode(text.toString(Charsets.UTF_8))
        }

        fun decode(text: String): Wording =
            try {
                yaml.decodeFromString(serializer(), text).withTrimmedText()
            } catch (error: YamlException) {
                throw PromptConfigException("Line ${error.line}, column ${error.column}: ${error.message}", error)
            }

        fun encode(wording: Wording): String = yaml.encodeToString(serializer(), wording).trimEnd() + "\n"

        /** Sets the description of each named, declared parameter; the schema is otherwise untouched. */
        fun withParameters(
            schema: JsonObject,
            parameters: Map<String, String>,
        ): JsonObject {
            val properties = schema["properties"] as? JsonObject ?: return schema
            if (parameters.isEmpty()) return schema
            val described =
                properties.mapValues { (name, property) ->
                    val description = parameters[name]
                    if (description != null &&
                        property is JsonObject
                    ) {
                        JsonObject(property + ("description" to JsonPrimitive(description)))
                    } else {
                        property
                    }
                }
            return JsonObject(schema + ("properties" to JsonObject(described)))
        }
    }
}
