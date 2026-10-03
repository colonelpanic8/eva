package com.colonelpanic.eva.data

import com.colonelpanic.eva.data.configuration.EvaConfiguration
import com.colonelpanic.eva.data.configuration.PortableSkill
import com.colonelpanic.eva.skills.Skill
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI

/** One installed skill as the settings screen and the skill tool see it. */
data class InstalledSkill(
    val files: PortableSkill,
    val skill: Skill,
    val enabled: Boolean,
)

@Serializable
private data class StoredSkills(
    val installed: List<PortableSkill> = emptyList(),
    val disabled: List<String> = emptyList(),
)

/** The user's skills. Portable: every change is written to the user configuration. */
class SkillSettings(
    private val files: MemoryFiles,
    private val onChanged: () -> Unit = {},
) {
    private var stored = read()
    private val mutable = MutableStateFlow(stored.installed())
    val state = mutable.asStateFlow()

    /** Skills the model may load, in name order. */
    fun enabled(): List<Skill> = state.value.filter { it.enabled }.map { it.skill }

    @Synchronized
    fun portable(): EvaConfiguration.Skills = EvaConfiguration.Skills(stored.installed, stored.disabled)

    /** Installs a skill, replacing one with the same name and keeping its on/off choice. */
    @Synchronized
    fun install(
        skill: String,
        openai: String?,
        source: String?,
    ): Skill {
        val origin = source?.trim()?.takeIf(String::isNotEmpty)
        origin?.let { require(URI(it).scheme == "https") { "A skill's source must be an HTTPS address." } }
        val portable = PortableSkill(skill, openai?.takeIf(String::isNotBlank), origin)
        val parsed = Skill.parse(portable.skill, portable.openai)
        val enabled = state.value.firstOrNull { it.skill.name == parsed.name }?.enabled ?: true
        save(state.value.filterNot { it.skill.name == parsed.name } + InstalledSkill(portable, parsed, enabled))
        return parsed
    }

    @Synchronized
    fun setEnabled(
        name: String,
        enabled: Boolean,
    ) = save(state.value.map { if (it.skill.name == name) it.copy(enabled = enabled) else it })

    @Synchronized
    fun remove(name: String) {
        stored = stored.copy(disabled = stored.disabled - name)
        save(state.value.filterNot { it.skill.name == name })
    }

    /** Restores the configured skills; a disabled name with no installed skill is kept for when it returns. */
    @Synchronized
    fun replace(configuration: EvaConfiguration.Skills) {
        val next = StoredSkills(configuration.installed, configuration.disabled)
        val installed = next.installed()
        write(next)
        stored = next
        mutable.value = installed
    }

    private fun save(skills: List<InstalledSkill>) {
        val sorted = skills.sortedBy { it.skill.name }
        val names = sorted.map { it.skill.name }.toSet()
        val retainedDisabled = stored.disabled.filter { it !in names }
        val next =
            StoredSkills(sorted.map { it.files }, (sorted.filterNot { it.enabled }.map { it.skill.name } + retainedDisabled).sorted())
        write(next)
        stored = next
        mutable.value = sorted
        onChanged()
    }

    private fun StoredSkills.installed(): List<InstalledSkill> =
        installed
            .map { files -> Skill.parse(files.skill, files.openai).let { InstalledSkill(files, it, it.name !in disabled) } }
            .sortedBy { it.skill.name }

    private fun read(): StoredSkills = files.read(FILE)?.let { Json.decodeFromString<StoredSkills>(it) } ?: StoredSkills()

    private fun write(skills: StoredSkills) = files.write(FILE, Json.encodeToString(skills))

    private companion object {
        const val FILE = "skills.json"
    }
}
