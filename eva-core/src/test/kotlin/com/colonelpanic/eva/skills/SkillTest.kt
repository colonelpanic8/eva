package com.colonelpanic.eva.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillTest {
    @Test
    fun `reads a Codex skill and its openai yaml, ignoring keys EVA does not use`() {
        val skill =
            Skill.parse(
                """
                ---
                name: password-reset
                description: >-
                  Use when the user wants to reset a password.
                license: Apache-2.0
                metadata:
                  author: someone
                  tags: [accounts, security]
                allowed-tools: Bash(pass:*)
                ---

                # Password reset

                Find the account first.
                """.trimIndent(),
                """
                interface:
                  display_name: "Password Reset"
                  short_description: "Reset passwords safely"
                  icon_small: "./assets/small.svg"
                  brand_color: "#3B82F6"
                  default_prompt: "Use ${'$'}password-reset."
                policy:
                  allow_implicit_invocation: false
                dependencies:
                  tools:
                    - type: "mcp"
                      value: "passwordManager"
                      transport: "streamable_http"
                      url: "https://example.com/mcp"
                """.trimIndent(),
            )

        assertEquals("password-reset", skill.name)
        assertEquals("Use when the user wants to reset a password.", skill.description)
        assertEquals("# Password reset\n\nFind the account first.", skill.body)
        assertEquals("Password Reset", skill.title)
        assertEquals("Reset passwords safely", skill.shortDescription)
        assertFalse(skill.implicit)
        assertEquals(listOf("passwordManager"), skill.dependencies)
    }

    @Test
    fun `a skill without openai yaml is used implicitly under its own name`() {
        val skill = Skill.parse("---\r\nname: journaling\r\ndescription: Reflect.\r\n---\r\nAsk one question at a time.\r\n")

        assertEquals("journaling", skill.title)
        assertNull(skill.shortDescription)
        assertTrue(skill.implicit)
        assertEquals("Ask one question at a time.", skill.body)
    }

    @Test
    fun `rejects files Codex would not load`() {
        listOf(
            "name: x\ndescription: y\n\nNo fence.",
            "---\ndescription: Missing name.\n---\nBody",
            "---\nname: Bad_Name\ndescription: y\n---\nBody",
            "---\nname: double--hyphen\ndescription: y\n---\nBody",
            "---\nname: no-description\n---\nBody",
            "---\nname: empty\ndescription: y\n---\n\n",
            "---\nname: long\ndescription: ${"x".repeat(1_025)}\n---\nBody",
        ).forEach { text -> assertThrows(text, IllegalArgumentException::class.java) { Skill.parse(text) } }
    }

    @Test
    fun `a pasted address resolves to the skill file and the openai yaml beside it`() {
        val expected =
            SkillSource(
                "https://raw.githubusercontent.com/me/skills/main/journaling/SKILL.md",
                "https://raw.githubusercontent.com/me/skills/main/journaling/agents/openai.yaml",
            )
        listOf(
            "https://github.com/me/skills/blob/main/journaling/SKILL.md",
            "https://github.com/me/skills/tree/main/journaling/",
            "https://raw.githubusercontent.com/me/skills/main/journaling/SKILL.md",
            " https://raw.githubusercontent.com/me/skills/main/journaling ",
        ).forEach { assertEquals(it, expected, SkillSource.of(it)) }
        listOf("http://example.com/SKILL.md", "https://example.com/SKILL.md?token=1", "not a url").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { SkillSource.of(it) }
        }
    }
}
