package com.colonelpanic.eva.providers

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderToolCatalogTest {
    private val catalog =
        ProviderToolCatalog("rev", emptyList(), List(25) { ExcludedTool("extension.x.a$it", "Say \"hi\" $it") })

    @Test
    fun `excluded names are bounded with a count of the rest and quoted for the model`() {
        assertEquals(
            "Text session · 25 tools unavailable: Say \"hi\" 0, Say \"hi\" 1, Say \"hi\" 2 and 22 more — see Extensions",
            catalog.sessionNotice("Text session"),
        )
        val note = catalog.excludedNames(ProviderToolCatalog.NOTE_NAMES, quoted = true)
        assertEquals((0 until 20).joinToString(", ") { "\"Say \\\"hi\\\" $it\"" } + " and 5 more", note)
        assertEquals("Say \"hi\" 0", catalog.copy(excludedTools = catalog.excludedTools.take(1)).excludedNames(3))
    }
}
