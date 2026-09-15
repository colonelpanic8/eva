package com.colonelpanic.eva.conversation.prompt

import com.colonelpanic.eva.providers.ProviderToolDefinition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Which kind of session a component is written for. */
@Serializable
enum class Applies {
    @SerialName("voice")
    VOICE,

    @SerialName("text")
    TEXT,

    @SerialName("both")
    BOTH,
    ;

    fun covers(voice: Boolean): Boolean = this == BOTH || (this == VOICE) == voice
}

/**
 * One layer of the system prompt. The instructions the model receives are nothing but the
 * enabled components that apply to the session, in file order, so there is no prompt text
 * anywhere that the file does not show. A component can also reword a tool it cares about
 * or drop tools it does not want offered, which is how a mode that changes when to hang up,
 * or one that makes the phone read-only, stays a single self-contained entry.
 */
@Serializable
data class PromptComponent(
    val id: String,
    val title: String = id,
    val summary: String = "",
    val enabled: Boolean = true,
    /** Components sharing a slot are alternatives: at most one of them is enabled. */
    val slot: String? = null,
    val applies: Applies = Applies.BOTH,
    val instruction: String = "",
    /** Replacement descriptions by tool id. Tools this phone does not offer are ignored. */
    val describe: Map<String, String> = emptyMap(),
    /** Tool ids withheld from the model while this component is on. */
    val hide: List<String> = emptyList(),
)

data class PromptContext(
    val voice: Boolean,
    /** Values for `{{name}}` references; a reference to anything else is a validation error. */
    val variables: Map<String, String>,
)

/** What a session actually sends: the joined instructions and the tool adjustments. */
data class AssembledPrompt(
    val instructions: String,
    val describe: Map<String, String>,
    val hidden: Set<String>,
) {
    fun apply(tools: List<ProviderToolDefinition>): List<ProviderToolDefinition> =
        tools
            .filterNot { it.capabilityId in hidden }
            .map { tool -> describe[tool.capabilityId]?.let { tool.copy(description = it) } ?: tool }
}

enum class VoiceCallMode(
    internal val componentId: String,
) {
    ONE_REQUEST(PromptDefaults.ONE_REQUEST_ID),
    OPEN_CONVERSATION(PromptDefaults.OPEN_CONVERSATION_ID),
    ;

    companion object {
        fun external(
            oneShot: Boolean,
            forceOneShot: Boolean = false,
        ): VoiceCallMode = if (oneShot || forceOneShot) ONE_REQUEST else OPEN_CONVERSATION
    }
}

@Serializable
data class PromptConfig(
    val components: List<PromptComponent> = emptyList(),
) {
    /** Answers the problem a user editing the file would want pointed out, or null. */
    fun problem(variables: Set<String>): String? {
        components.forEach { component ->
            if (!ID.matches(component.id)) return "“${component.id}” is not a valid id: use lowercase letters, digits, dashes, and slashes."
            REFERENCE.findAll(component.instruction).forEach { match ->
                val name = match.groupValues[1]
                if (name !in variables) {
                    return "“${component.id}” refers to {{$name}}; the variables are ${variables.sorted().joinToString { "{{$it}}" }}."
                }
            }
        }
        components
            .groupBy { it.id }
            .entries
            .firstOrNull { it.value.size > 1 }
            ?.let { return "More than one component has the id “${it.key}”." }
        components
            .filter { it.enabled && it.slot != null }
            .groupBy { it.slot }
            .entries
            .firstOrNull { it.value.size > 1 }
            ?.let { return "Only one component in slot “${it.key}” can be enabled; ${it.value.joinToString { c -> "“${c.id}”" }} all are." }
        return null
    }

    fun validated(variables: Set<String>): PromptConfig = problem(variables)?.let { throw PromptConfigException(it) } ?: this

    fun assemble(context: PromptContext): AssembledPrompt {
        val active = components.filter { it.enabled && it.applies.covers(context.voice) }
        val instructions =
            active
                .map { paragraphs(substitute(it.instruction, context.variables)) }
                .filter { it.isNotEmpty() }
                .joinToString("\n\n")
        return AssembledPrompt(
            instructions,
            active.fold(emptyMap()) { acc, component -> acc + component.describe },
            active.flatMap { it.hide }.toSet(),
        )
    }

    fun selectCallMode(mode: VoiceCallMode): PromptConfig {
        val target =
            components.firstOrNull { it.id == mode.componentId }
                ?: throw PromptConfigException("The prompt is missing the ${mode.componentId} call component.")
        if (target.slot != "call") throw PromptConfigException("The ${mode.componentId} component must use the call slot.")
        return toggle(target.id, true)
    }

    /** Turning a slot member on turns its alternatives off, so a slot never has two. */
    fun toggle(
        id: String,
        enabled: Boolean,
    ): PromptConfig {
        val target = components.firstOrNull { it.id == id } ?: return this
        return copy(
            components =
                components.map { component ->
                    when {
                        component.id == id -> component.copy(enabled = enabled)
                        enabled && target.slot != null && component.slot == target.slot -> component.copy(enabled = false)
                        else -> component
                    }
                },
        )
    }

    /** Replaces the component with the same id in place, or appends a new one. */
    fun upsert(component: PromptComponent): PromptConfig =
        if (components.any { it.id == component.id }) {
            copy(components = components.map { if (it.id == component.id) component else it })
        } else {
            copy(components = components + component)
        }

    fun remove(id: String): PromptConfig = copy(components = components.filterNot { it.id == id })

    // Not private: the generated serializer lives on the companion.
    companion object {
        private val ID = Regex("[a-z0-9][a-z0-9/-]*")

        // Android's regex engine rejects an unescaped closing brace, where the desktop JVM's allows it.
        private val REFERENCE = Regex("""\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}\}""")

        private fun substitute(
            text: String,
            variables: Map<String, String>,
        ) = REFERENCE.replace(text) { match -> variables[match.groupValues[1]] ?: match.value }

        /** Wrapped lines are one paragraph; a blank line separates paragraphs, as in Markdown. */
        private fun paragraphs(text: String): String =
            text
                .split(Regex("""\n\s*\n"""))
                .map { it.split(Regex("""\s+""")).filter(String::isNotEmpty).joinToString(" ") }
                .filter { it.isNotEmpty() }
                .joinToString("\n\n")
    }
}

class PromptConfigException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
