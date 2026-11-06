package com.colonelpanic.eva.capability

import com.colonelpanic.eva.conversation.prompt.Wording
import com.colonelpanic.eva.providers.ProviderToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** How often a call is read back to the user before it runs. */
enum class ConfirmLevel(
    val wire: String,
) {
    NEVER("never"),

    /** Only when a listed parameter was neither stated by the user nor taken from their defaults. */
    UNRESOLVED("unresolved"),
    ALWAYS("always"),
    ;

    companion object {
        fun of(value: String): ConfirmLevel? = entries.firstOrNull { it.wire == value }
    }
}

enum class ParameterResolve(
    val wire: String,
) {
    /** The model may propose a value; the user confirms or changes it. */
    CONFIRM("confirm"),

    /** The model must not propose a value; the user gives it. */
    ASK("ask"),
    ;

    companion object {
        fun of(value: String): ParameterResolve? = entries.firstOrNull { it.wire == value }
    }
}

data class ConfirmedParameter(
    val resolve: ParameterResolve,
    val label: String? = null,
    /** A tool that lists valid values; a hint for the model, not a binding. */
    val choicesFrom: String? = null,
)

/** A capability's declaration of which parameters the user should settle before it runs. */
data class ToolConfirmation(
    val parameters: Map<String, ConfirmedParameter>,
    val level: ConfirmLevel = ConfirmLevel.UNRESOLVED,
    /** `{parameter}` placeholders take the resolved values. */
    val readout: String? = null,
)

/** The user's portable choices for one capability: `tools.<capabilityId>` in the configuration. */
data class ToolPreferences(
    val confirm: ConfirmLevel? = null,
    val defaults: Map<String, String> = emptyMap(),
)

/**
 * Fills the user's defaults and holds calls whose listed parameters the user has not settled.
 * A held call is journaled NOT_EXECUTED with a readout and a token; repeating the same resolved
 * call with that token runs it. The token is bound to the resolved arguments, so a changed call
 * is read back again. This is a conversational check, not an authorization boundary.
 */
class ConfirmationGate(
    private val preferences: () -> Map<String, ToolPreferences> = { emptyMap() },
    private val wording: () -> Wording = { Wording.bundled },
) {
    sealed interface Review {
        val proposal: ToolProposal

        data class Proceed(
            override val proposal: ToolProposal,
        ) : Review

        data class Hold(
            override val proposal: ToolProposal,
            val message: String,
            val data: JsonObject,
        ) : Review
    }

    fun effective(definition: CapabilityDefinition): ToolConfirmation? = effective(definition, preferences()[definition.id])

    fun review(
        proposal: ToolProposal,
        definition: CapabilityDefinition?,
    ): Review {
        definition ?: return Review.Proceed(proposal)
        val preference = preferences()[definition.id]
        val confirmation = effective(definition, preference) ?: return Review.Proceed(proposal)
        val properties = definition.properties()
        val stated = stated(proposal.arguments[STATED])
        val token = proposal.arguments[CONFIRM]
        val supplied = proposal.arguments - STATED - CONFIRM
        val defaulted =
            preference
                ?.defaults
                .orEmpty()
                .filterKeys { it in properties && it !in supplied }
        val arguments = supplied + defaulted
        val resolved = proposal.copy(arguments = arguments)
        val expected = token(definition.id, arguments)
        if (token == expected || confirmation.level == ConfirmLevel.NEVER) return Review.Proceed(resolved)
        val unresolved = confirmation.parameters.filterKeys { it !in stated && it !in defaulted }
        if (confirmation.level == ConfirmLevel.UNRESOLVED && unresolved.isEmpty()) return Review.Proceed(resolved)
        val readout = readout(definition, confirmation, arguments)
        val text = wording()
        val message =
            text
                .message(Wording.CONFIRMATION_REQUIRED)
                .replace("{readout}", readout)
                .replace("{token}", expected)
        val ignored = preference?.defaults.orEmpty().keys - properties.keys
        val data =
            buildJsonObject {
                put("reason", "confirmation_required")
                put("capabilityId", definition.id)
                put("readout", readout)
                put(CONFIRM, expected)
                put("arguments", JsonObject(arguments.mapValues { JsonPrimitive(it.value) }))
                put(
                    "unresolved",
                    buildJsonArray {
                        unresolved.forEach { (name, parameter) ->
                            add(
                                buildJsonObject {
                                    put("name", name)
                                    parameter.label?.let { put("label", it) }
                                    put("resolve", parameter.resolve.wire)
                                    arguments[name]?.let { put("proposed", it) }
                                    (properties[name] as? JsonObject)?.get("enum")?.let { put("choices", it) }
                                    parameter.choicesFrom?.let { put("choicesFrom", it) }
                                },
                            )
                        }
                    },
                )
                if (defaulted.isNotEmpty()) put("defaulted", JsonArray(defaulted.keys.map(::JsonPrimitive)))
                if (ignored.isNotEmpty()) put("ignoredDefaults", JsonArray(ignored.map(::JsonPrimitive)))
            }
        return Review.Hold(resolved, message, data)
    }

    /** Adds the stated-parameter and token arguments, and EVA's note, to a tool offered to a model. */
    fun offer(
        tool: ProviderToolDefinition,
        definition: CapabilityDefinition,
    ): ProviderToolDefinition {
        val confirmation = effective(definition) ?: return tool
        if (confirmation.level == ConfirmLevel.NEVER) return tool
        val properties = tool.inputSchema["properties"] as? JsonObject ?: return tool
        val text = wording()
        val controls =
            listOfNotNull(
                (
                    STATED to
                        buildJsonObject {
                            put("type", "array")
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", "string")
                                    put("enum", JsonArray(properties.keys.map(::JsonPrimitive)))
                                },
                            )
                            put("maxItems", properties.size)
                            put("description", text.message(Wording.CONFIRMATION_STATED))
                        }
                ).takeIf { properties.isNotEmpty() },
                CONFIRM to
                    buildJsonObject {
                        put("type", "string")
                        put("maxLength", TOKEN_LENGTH)
                        put("description", text.message(Wording.CONFIRMATION_TOKEN))
                    },
            ).toMap()
        return tool.copy(
            description = tool.description + "\n\n" + text.message(Wording.CONFIRMATION_NOTE).replace("{id}", definition.id),
            inputSchema = JsonObject(tool.inputSchema + ("properties" to JsonObject(properties + controls))),
        )
    }

    companion object {
        const val STATED = "_stated"
        const val CONFIRM = "_confirm"
        private const val TOKEN_LENGTH = 16
        private const val VALUE_LIMIT = 160

        /** A schema already at the property limit cannot carry the two control arguments. */
        private const val MAX_DECLARED = ToolSchema.MAX_INPUT_ITEMS - 2

        fun effective(
            definition: CapabilityDefinition,
            preference: ToolPreferences?,
        ): ToolConfirmation? {
            if (definition.properties().size > MAX_DECLARED) return null
            val declared = definition.confirmation
            val level = preference?.confirm ?: declared?.level
            return when {
                declared != null -> declared.copy(level = level ?: declared.level)
                level != null && level != ConfirmLevel.NEVER -> ToolConfirmation(emptyMap(), level)
                preference?.defaults?.isNotEmpty() == true -> ToolConfirmation(emptyMap(), ConfirmLevel.NEVER)
                else -> null
            }
        }

        fun token(
            capabilityId: String,
            arguments: Map<String, String>,
        ): String {
            val fields = listOf(capabilityId) + arguments.toSortedMap().flatMap { listOf(it.key, it.value) }
            val encoded = fields.joinToString("") { "${it.length}:$it" }
            return MessageDigest
                .getInstance("SHA-256")
                .digest(encoded.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(TOKEN_LENGTH)
        }

        fun readout(
            definition: CapabilityDefinition,
            confirmation: ToolConfirmation,
            arguments: Map<String, String>,
        ): String {
            confirmation.readout?.let { template ->
                return Regex("\\{([A-Za-z][A-Za-z0-9_]{0,63})\\}").replace(template) { match ->
                    arguments[match.groupValues[1]]?.let(::shorten) ?: "(not set)"
                }
            }
            val listed = confirmation.parameters.keys + arguments.keys
            return definition.title + ": " +
                listed.joinToString("; ") { name ->
                    val label = confirmation.parameters[name]?.label ?: name
                    "$label = ${arguments[name]?.let(::shorten) ?: "(not set)"}"
                }
        }

        private fun shorten(value: String) = if (value.length <= VALUE_LIMIT) value else value.take(VALUE_LIMIT - 1) + "…"

        private fun stated(value: String?): Set<String> {
            value ?: return emptySet()
            val parsed = runCatching { Json.parseToJsonElement(value) as? JsonArray }.getOrNull()
            return parsed?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()
                ?: value
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
        }

        private fun CapabilityDefinition.properties(): JsonObject = inputSchema["properties"] as? JsonObject ?: JsonObject(emptyMap())
    }
}
