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
        const val EXTENSION_GUIDANCE = "extension-guidance"
        const val CATALOG_UNAVAILABLE = "catalog-unavailable"
        const val ENDS_CALL_IMMEDIATELY = "ends-call-immediately"
        const val ENDS_CALL_AFTER_REPLY = "ends-call-after-reply"
        const val MESSAGING_BRIDGES = "messaging-bridges"
        const val DEVICE_TASK_REVISED = "device-task-revised"
        const val DEVICE_TASK_STOPPING = "device-task-stopping"
        const val DEVICE_TASK_NONE = "device-task-none"
        const val DEVICE_TASK_INVALID = "device-task-invalid"
        const val HANDOFF_INVALID = "handoff-invalid"
        const val HANDOFF_STARTED = "handoff-started"
        const val HANDOFF_TIMEOUT = "handoff-timeout"
        const val BACKGROUND_RESPONSE_CANCELLED = "background-response-cancelled"
        const val HANDOFF_FAILED = "handoff-failed"
        const val HANDOFF_INSTRUCTIONS = "handoff-instructions"
        const val BACKGROUND_STATUS = "background-status"
        const val BACKGROUND_INVALID = "background-invalid"
        const val BACKGROUND_NONE = "background-none"
        const val BACKGROUND_STOPPING = "background-stopping"
        const val BACKGROUND_UPDATE = "background-update"
        const val ANNOUNCEMENT_ACTION = "announcement-action"
        const val MUTATION_UNCERTAIN = "mutation-uncertain"
        const val UNKNOWN_ORIGIN = "unknown-origin"
        const val UNOWNED_ACTION = "unowned-action"
        const val DEVICE_RELEASE_UNCERTAIN = "device-release-uncertain"
        const val FORCE_STOP_ABANDONED = "force-stop-abandoned"
        const val TURN_FORCE_STOPPED = "turn-force-stopped"
        const val TURN_STOP_REQUESTED = "turn-stop-requested"
        const val RESPONSE_FAILED = "response-failed"
        const val BACKGROUND_CONNECTION_ENDED = "background-connection-ended"
        const val BACKGROUND_CONTINUATION_FAILED = "background-continuation-failed"
        const val BACKGROUND_RESTART_REFUSED = "background-restart-refused"
        const val TURN_STOPPED = "turn-stopped"
        const val RECEIPT_UNAVAILABLE = "receipt-unavailable"

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
