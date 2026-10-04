package com.colonelpanic.eva.data

import com.colonelpanic.eva.data.configuration.EvaConfiguration
import com.colonelpanic.eva.data.configuration.PortableSkill
import com.colonelpanic.eva.data.configuration.RepositorySkill
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
    /** The skill's folder in the configuration repository, or null for one installed in EVA. */
    val folder: String? = null,
)

data class SkillLibrary(
    val skills: List<InstalledSkill> = emptyList(),
    /** Why skill folders in the configuration repository were not loaded. */
    val problems: List<String> = emptyList(),
)

@Serializable
private data class StoredSkills(
    val installed: List<PortableSkill> = emptyList(),
    val disabled: List<String> = emptyList(),
    val repository: List<RepositorySkill> = emptyList(),
    val problems: List<String> = emptyList(),
)

/**
 * The user's skills: ones installed in EVA, kept in `eva.yaml`, and skill folders in the
 * configuration repository, which only the repository changes. Every edit is portable.
 */
class SkillSettings(
    private val files: MemoryFiles,
    private val onChanged: () -> Unit = {},
) {
    private var stored = read()
    private val mutable = MutableStateFlow(stored.library())
    val state = mutable.asStateFlow()

    /** Skills the model may load, in name order. */
    fun enabled(): List<Skill> =
        state.value.skills
            .filter { it.enabled }
            .map { it.skill }

    @Synchronized
    fun portable(): EvaConfiguration.Skills = EvaConfiguration.Skills(stored.installed, stored.disabled, stored.repository, stored.problems)

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
        state.value.skills.firstOrNull { it.skill.name == parsed.name && it.folder != null }?.let {
            throw IllegalArgumentException("${parsed.name} comes from ${it.folder} in your configuration repository; change it there.")
        }
        save(stored.copy(installed = stored.installed.filterNot { it.name == parsed.name } + portable))
        return parsed
    }

    @Synchronized
    fun setEnabled(
        name: String,
        enabled: Boolean,
    ) {
        save(stored.copy(disabled = if (enabled) stored.disabled - name else (stored.disabled + name).distinct()))
    }

    @Synchronized
    fun remove(name: String) {
        require(state.value.skills.none { it.skill.name == name && it.folder != null }) {
            "$name comes from your configuration repository; delete its folder there."
        }
        save(stored.copy(installed = stored.installed.filterNot { it.name == name }, disabled = stored.disabled - name))
    }

    /** Restores the configured skills; a disabled name with no skill is kept for when it returns. */
    @Synchronized
    fun replace(configuration: EvaConfiguration.Skills) {
        val next = StoredSkills(configuration.installed, configuration.disabled, configuration.repository, configuration.problems)
        val library = next.library()
        write(next)
        stored = next
        mutable.value = library
    }

    /** Skill folders belong to the repository they came from, so unlinking it drops them. */
    @Synchronized
    fun forgetRepository() {
        if (stored.repository.isEmpty() && stored.problems.isEmpty()) return
        val next = stored.copy(repository = emptyList(), problems = emptyList())
        val library = next.library()
        write(next)
        stored = next
        mutable.value = library
    }

    private fun save(next: StoredSkills) {
        val sorted = next.copy(installed = next.installed.sortedBy { it.name }, disabled = next.disabled.sorted())
        val library = sorted.library()
        write(sorted)
        stored = sorted
        mutable.value = library
        onChanged()
    }

    private fun StoredSkills.library(): SkillLibrary {
        fun entry(
            files: PortableSkill,
            folder: String?,
        ) = Skill.parse(files.skill, files.openai).let { InstalledSkill(files, it, it.name !in disabled, folder) }
        return SkillLibrary(
            (installed.map { entry(it, null) } + repository.map { entry(PortableSkill(it.skill, it.openai), it.path) })
                .sortedBy { it.skill.name },
            problems,
        )
    }

    private fun read(): StoredSkills = files.read(FILE)?.let { Json.decodeFromString<StoredSkills>(it) } ?: StoredSkills()

    private fun write(skills: StoredSkills) = files.write(FILE, Json.encodeToString(skills))

    private companion object {
        const val FILE = "skills.json"
    }
}
