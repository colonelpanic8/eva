package com.colonelpanic.eva.capability

import com.colonelpanic.eva.conversation.prompt.Wording
import com.colonelpanic.eva.skills.Skill
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillCapabilitiesTest {
    private val planning = Skill.parse("---\nname: daily-planning\ndescription: Plan the day.\n---\nRead the agenda first.")
    private val packing =
        Skill.parse(
            "---\nname: packing\ndescription: Pack for a trip.\n---\nCheck the weather.",
            "policy:\n  allow_implicit_invocation: false\n",
        )
    private val backend = SkillCapabilities.backend({ listOf(planning, packing) }, { Wording.bundled })

    @Test
    fun `using a skill returns its instructions`() =
        runTest {
            val outcome = backend.execute(mapOf("name" to "\$daily-planning"))

            assertEquals(InvocationStatus.COMPLETED, outcome.status)
            assertTrue(outcome.message.endsWith("\n\nRead the agenda first."))
            assertEquals("daily-planning", outcome.data!!["name"]!!.jsonPrimitive.content)
        }

    @Test
    fun `an unknown skill loads nothing and names the enabled ones`() =
        runTest {
            val outcome = backend.execute(mapOf("name" to "groceries"))

            assertEquals(InvocationStatus.NOT_EXECUTED, outcome.status)
            assertTrue(outcome.message.contains("groceries"))
            assertTrue(outcome.message.contains("daily-planning, packing"))
        }

    @Test
    fun `the catalog marks skills that run only when named`() {
        val catalog = SkillCapabilities.catalog(listOf(planning, packing))

        assertEquals(null, catalog[0].jsonObject["onlyWhenNamed"])
        assertEquals("true", catalog[1].jsonObject["onlyWhenNamed"]!!.jsonPrimitive.content)
    }
}
