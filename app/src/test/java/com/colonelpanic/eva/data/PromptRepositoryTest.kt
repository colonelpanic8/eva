package com.colonelpanic.eva.data

import com.colonelpanic.eva.conversation.prompt.PromptComponent
import com.colonelpanic.eva.conversation.prompt.PromptConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptRepositoryTest {
    private val yaml =
        """
        components:
        - id: identity
          instruction: Remote identity
        - id: new-default
          instruction: New instruction
        """.trimIndent()

    @Test
    fun `a repository loads a bounded validated prompt from https`() {
        var requested = ""
        var limit = 0
        val repository =
            PromptRepository { url, maxBytes ->
                requested = url
                limit = maxBytes
                yaml.toByteArray()
            }
        val loaded = repository.load("  https://instructions.example.test/prompt.yaml  ")
        assertEquals("https://instructions.example.test/prompt.yaml", requested)
        assertEquals(PromptRepository.MAX_BYTES, limit)
        assertEquals(requested, loaded.source)
        assertEquals(listOf("identity", "new-default"), loaded.config.components.map { it.id })

        val oversized = PromptRepository { _, _ -> ByteArray(PromptRepository.MAX_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { oversized.load(requested) }
        assertThrows(IllegalArgumentException::class.java) { repository.load("http://instructions.example.test/prompt.yaml") }
        assertThrows(IllegalArgumentException::class.java) { repository.load("https://user@example.test/prompt.yaml") }
        assertThrows(IllegalArgumentException::class.java) { repository.load("https://example.test/prompt.yaml#old") }
    }

    @Test
    fun `repository updates keep switches while replacing catalog content`() {
        val current =
            PromptConfig(
                listOf(
                    PromptComponent("identity", enabled = false, instruction = "Local edit"),
                    PromptComponent("local-only", enabled = true),
                ),
            )
        val remote = PromptRepository { _, _ -> yaml.toByteArray() }.load("https://example.test/prompt.yaml").config
        val merged = mergePromptUpdate(current, remote)
        assertEquals(listOf("identity", "new-default"), merged.components.map { it.id })
        assertFalse(merged.components.first().enabled)
        assertEquals("Remote identity", merged.components.first().instruction)
        assertTrue(merged.components.last().enabled)
    }
}
