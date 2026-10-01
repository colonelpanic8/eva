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
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class DesktopHostTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `the host starts from empty storage with the desktop prompt and tools`() =
        runBlocking {
            val paths = DesktopPaths(folder.root.resolve("config"), folder.root.resolve("data"))
            val lock = checkNotNull(paths.lock())
            DesktopHost(paths, Dispatchers.Default, lock) { Opening.Opened }.use { host ->
                val state = host.controller.state.first { !it.isLoading }
                assertNull(state.errorMessage)
                assertFalse(host.tokens.signedIn)
                assertNull("A second process must not recover the journal while this one runs", paths.lock())
            }
            assertFalse(lock.isValid)
            val again = checkNotNull(paths.lock())
            again.release()
            again.channel().close()
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
                        Opening.Opened
                    }.getValue(DesktopCapabilities.OPEN_URL)

            assertEquals(InvocationStatus.HANDED_OFF, backend.execute(mapOf("url" to "https://example.org/a")).status)
            assertEquals(InvocationStatus.NOT_EXECUTED, backend.execute(mapOf("url" to "file:///etc/passwd")).status)
            assertEquals(InvocationStatus.NOT_EXECUTED, backend.execute(mapOf("url" to "https://user@example.org")).status)
            assertEquals(listOf(URI("https://example.org/a")), opened)

            val refusing = DesktopCapabilities.backends { Opening.Refused("no browser") }.getValue(DesktopCapabilities.OPEN_URL)
            assertEquals(InvocationStatus.FAILED, refusing.execute(mapOf("url" to "https://example.org")).status)
            val lingering = DesktopCapabilities.backends { Opening.Pending }.getValue(DesktopCapabilities.OPEN_URL)
            assertEquals(InvocationStatus.UNKNOWN, lingering.execute(mapOf("url" to "https://example.org")).status)
        }

    @Test
    fun `storage is readable only by the user`() {
        val paths = DesktopPaths(folder.root.resolve("config"), folder.root.resolve("data"))
        paths.data.mkdirs()
        paths.journal.writeText("")
        Files.setPosixFilePermissions(paths.journal.toPath(), PosixFilePermissions.fromString("rw-r--r--"))

        paths.secure()

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(paths.data.toPath())))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(paths.config.toPath())))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(paths.journal.toPath())))
    }
}
