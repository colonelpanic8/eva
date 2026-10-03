package com.colonelpanic.eva.skills

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A skill in the Codex format: the exact `SKILL.md` text (Agent Skills frontmatter and a
 * Markdown body) and, optionally, the exact `agents/openai.yaml` beside it. EVA keeps both texts
 * as written so a skill moves between EVA and Codex unchanged; keys EVA does not use are ignored,
 * as Codex ignores them.
 */
data class Skill(
    val name: String,
    val description: String,
    val body: String,
    val displayName: String? = null,
    val shortDescription: String? = null,
    /** False when the skill should run only when the user names it. */
    val implicit: Boolean = true,
    /** Tools the skill's `openai.yaml` says it needs, by their declared value. */
    val dependencies: List<String> = emptyList(),
) {
    val title: String get() = displayName ?: name

    companion object {
        /** The Agent Skills limits on the frontmatter Codex checks; the body is unbounded, as in Codex. */
        const val MAX_DESCRIPTION_CHARS = 1_024
        private const val MAX_NAME_CHARS = 64
        private val NAME = Regex("[a-z0-9]+(-[a-z0-9]+)*")
        private val FENCE = Regex("""\A---[ \t]*\r?\n(.*?)\r?\n---[ \t]*(?:\r?\n|\z)""", RegexOption.DOT_MATCHES_ALL)

        private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

        fun isName(value: String) = value.length <= MAX_NAME_CHARS && NAME.matches(value)

        fun parse(
            skill: String,
            openai: String? = null,
        ): Skill {
            val fence = requireNotNull(FENCE.find(skill.removePrefix(""))) { "SKILL.md must start with --- frontmatter." }
            val front = decode(Frontmatter.serializer(), fence.groupValues[1], "SKILL.md frontmatter")
            val name = requireNotNull(front.name?.trim()) { "SKILL.md frontmatter needs a name." }
            require(
                isName(name),
            ) { "Skill name “$name” must be lowercase letters, digits, and single hyphens, up to $MAX_NAME_CHARS characters." }
            val description = front.description?.trim().orEmpty()
            require(description.isNotEmpty()) { "SKILL.md frontmatter needs a description." }
            require(description.length <= MAX_DESCRIPTION_CHARS) { "Skill description is longer than $MAX_DESCRIPTION_CHARS characters." }
            val body = skill.removePrefix("﻿").substring(fence.range.last + 1).trim()
            require(body.isNotEmpty()) { "SKILL.md has no instructions after its frontmatter." }
            val agent =
                openai
                    ?.takeIf(String::isNotBlank)
                    ?.let { decode(OpenAiAgent.serializer(), it, "agents/openai.yaml") }
                    ?: OpenAiAgent()
            return Skill(
                name,
                description,
                body,
                agent.interfaceText
                    ?.displayName
                    ?.trim()
                    ?.takeIf(String::isNotEmpty),
                agent.interfaceText
                    ?.shortDescription
                    ?.trim()
                    ?.takeIf(String::isNotEmpty),
                agent.policy?.allowImplicitInvocation ?: true,
                agent.dependencies
                    ?.tools
                    .orEmpty()
                    .mapNotNull { it.value?.trim()?.takeIf(String::isNotEmpty) },
            )
        }

        private fun <T> decode(
            serializer: kotlinx.serialization.KSerializer<T>,
            text: String,
            label: String,
        ): T =
            try {
                yaml.decodeFromString(serializer, text)
            } catch (error: YamlException) {
                throw IllegalArgumentException("$label, line ${error.line}: ${error.message}", error)
            } catch (error: kotlinx.serialization.SerializationException) {
                throw IllegalArgumentException("$label: ${error.message}", error)
            }
    }
}

@Serializable
private data class Frontmatter(
    val name: String? = null,
    val description: String? = null,
)

@Serializable
private data class OpenAiAgent(
    @SerialName("interface") val interfaceText: Interface? = null,
    val policy: Policy? = null,
    val dependencies: Dependencies? = null,
) {
    @Serializable
    data class Interface(
        @SerialName("display_name") val displayName: String? = null,
        @SerialName("short_description") val shortDescription: String? = null,
    )

    @Serializable
    data class Policy(
        @SerialName("allow_implicit_invocation") val allowImplicitInvocation: Boolean? = null,
    )

    @Serializable
    data class Dependencies(
        val tools: List<Tool>? = null,
    )

    @Serializable
    data class Tool(
        val value: String? = null,
    )
}
