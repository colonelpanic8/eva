package com.colonelpanic.eva.data

import com.colonelpanic.eva.data.configuration.EvaConfiguration
import com.colonelpanic.eva.data.configuration.PortableSkill
import com.colonelpanic.eva.data.configuration.RepositorySkill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SkillSettingsTest {
    private val files =
        object : MemoryFiles {
            val texts = mutableMapOf<String, String>()

            override fun read(name: String) = texts[name]

            override fun write(
                name: String,
                text: String,
            ) {
                texts[name] = text
            }
        }

    private fun skill(
        name: String,
        body: String = "Do it.",
    ) = "---\nname: $name\ndescription: About $name.\n---\n$body\n"

    @Test
    fun `reinstalling keeps the switch, removing forgets it, and every edit is reported`() {
        var changes = 0
        val settings = SkillSettings(files) { changes++ }
        settings.install(skill("packing"), null, null)
        settings.install(skill("journaling"), null, "https://example.com/journaling/SKILL.md")
        settings.setEnabled("packing", false)
        settings.install(skill("packing", "Check the weather."), null, null)

        assertEquals(listOf("journaling"), settings.enabled().map { it.name })
        assertEquals(listOf("packing"), settings.portable().disabled)
        assertEquals(
            "Check the weather.",
            settings.state.value.skills
                .single { it.skill.name == "packing" }
                .skill.body,
        )

        settings.remove("packing")
        assertEquals(
            EvaConfiguration.Skills(listOf(PortableSkill(skill("journaling"), null, "https://example.com/journaling/SKILL.md"))),
            settings.portable(),
        )
        assertEquals(5, changes)
        assertEquals(settings.portable(), SkillSettings(files).portable())
        assertThrows(IllegalArgumentException::class.java) { settings.install(skill("x"), null, "http://example.com/SKILL.md") }
    }

    @Test
    fun `a restore keeps a switched-off name whose skill is not installed here`() {
        val settings = SkillSettings(files)
        val restored = EvaConfiguration.Skills(listOf(PortableSkill(skill("journaling"))), listOf("from-shared-base"))
        settings.replace(restored)

        assertEquals(restored, settings.portable())
        assertEquals(listOf("journaling"), settings.enabled().map { it.name })
        settings.setEnabled("journaling", false)
        assertEquals(listOf("from-shared-base", "journaling"), settings.portable().disabled)
    }

    @Test
    fun `repository skill folders change only through the repository and leave when it is unlinked`() {
        val settings = SkillSettings(files)
        settings.replace(
            EvaConfiguration.Skills(
                repository = listOf(RepositorySkill(".agents/skills/journaling", skill("journaling"))),
                problems = listOf("Skill folder skills/broken was not loaded: no frontmatter"),
            ),
        )

        assertEquals(
            ".agents/skills/journaling",
            settings.state.value.skills
                .single()
                .folder,
        )
        assertThrows(IllegalArgumentException::class.java) { settings.install(skill("journaling", "Other."), null, null) }
        assertThrows(IllegalArgumentException::class.java) { settings.remove("journaling") }
        settings.setEnabled("journaling", false)
        assertEquals(emptyList<String>(), settings.enabled().map { it.name })
        assertEquals(listOf("journaling"), settings.portable().disabled)

        settings.forgetRepository()
        assertEquals(EvaConfiguration.Skills(disabled = listOf("journaling")), settings.portable())
        assertEquals(SkillLibrary(), settings.state.value)
    }
}
