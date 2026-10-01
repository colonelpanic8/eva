package com.colonelpanic.eva.desktop

import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryCapabilities
import com.colonelpanic.eva.conversation.prompt.Wording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI

class DesktopHostTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `the host starts from empty storage with the desktop prompt and tools`() =
        runBlocking {
            val paths = DesktopPaths(folder.root.resolve("config"), folder.root.resolve("data"))
            DesktopHost(paths, Dispatchers.Default) { null }.use { host ->
                val state = host.controller.state.first { !it.isLoading }
                assertNull(state.errorMessage)
                assertFalse(host.tokens.signedIn)
            }
            val identity =
                DesktopHost.prompt.components
                    .first { it.id == "identity" }
                    .instruction
                    .orEmpty()
            assertFalse(identity.contains("Android"))
            assertTrue(paths.journal.isFile)
        }

    @Test
    fun `every desktop tool has catalog wording`() {
        (DesktopCapabilities.definitions + MemoryCapabilities.definitions).forEach { definition ->
            assertTrue(definition.id, definition.description.isNotBlank())
        }
        val desktop = DesktopCapabilities.definitions.associateBy { it.id }
        Wording.bundled.tools.filterKeys { it.startsWith("eva.desktop.") }.forEach { (id, text) ->
            val declared = (requireNotNull(desktop[id]) { "$id is not a desktop tool" }.inputSchema["properties"] as Map<*, *>).keys
            text.parameters.keys.forEach { assertTrue("$id has no parameter $it", it in declared) }
        }
    }

    @Test
    fun `opening a web address is a handoff and anything else is refused`() =
        runBlocking {
            val opened = mutableListOf<URI>()
            val backend =
                DesktopCapabilities
                    .backends {
                        opened += it
                        null
                    }.getValue(DesktopCapabilities.OPEN_URL)

            assertEquals(InvocationStatus.HANDED_OFF, backend.execute(mapOf("url" to "https://example.org/a")).status)
            assertEquals(InvocationStatus.NOT_EXECUTED, backend.execute(mapOf("url" to "file:///etc/passwd")).status)
            assertEquals(InvocationStatus.NOT_EXECUTED, backend.execute(mapOf("url" to "https://user@example.org")).status)
            assertEquals(listOf(URI("https://example.org/a")), opened)

            val refusing = DesktopCapabilities.backends { "no browser" }.getValue(DesktopCapabilities.OPEN_URL)
            assertEquals(InvocationStatus.FAILED, refusing.execute(mapOf("url" to "https://example.org")).status)
        }
}
