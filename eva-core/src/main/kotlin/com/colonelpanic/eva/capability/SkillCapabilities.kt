package com.colonelpanic.eva.capability

import com.colonelpanic.eva.conversation.prompt.Wording
import com.colonelpanic.eva.skills.Skill
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Skills load on demand, as in Codex: the model sees each enabled skill's name and description on
 * this tool and reads a skill's instructions by using it. A skill is text; it adds no tools or authority.
 */
object SkillCapabilities {
    const val USE = "eva.skills.use"

    val definition =
        tool(
            USE,
            "Use a skill",
            Json
                .parseToJsonElement(
                    """{"type":"object","properties":{"name":{"type":"string","minLength":1,"maxLength":64}},"required":["name"],"additionalProperties":false}""",
                ).jsonObject,
            readOnly = true,
        )

    /** What the model is told about each enabled skill. */
    fun catalog(skills: List<Skill>): JsonArray =
        JsonArray(
            skills.map { skill ->
                buildJsonObject {
                    put("name", skill.name)
                    put("description", skill.description)
                    if (!skill.implicit) put("onlyWhenNamed", true)
                }
            },
        )

    fun backend(
        enabled: () -> List<Skill>,
        wording: () -> Wording,
    ): ExecutionBackend =
        object : ExecutionBackend {
            override suspend fun unavailableReason(): String? = null

            override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                val skills = enabled()
                val name = arguments.getValue("name").trim().removePrefix("$")
                val skill =
                    skills.firstOrNull { it.name == name }
                        ?: return ExecutionOutcome(
                            InvocationStatus.NOT_EXECUTED,
                            wording()
                                .message(Wording.SKILL_UNKNOWN)
                                .replace("{name}", name)
                                .replace("{skills}", skills.joinToString(", ") { it.name }.ifEmpty { "none" }),
                        )
                return ExecutionOutcome(
                    InvocationStatus.COMPLETED,
                    wording().message(Wording.SKILL_LOADED).replace("{name}", skill.name) + "\n\n" + skill.body,
                    buildJsonObject { put("name", skill.name) },
                )
            }
        }
}
