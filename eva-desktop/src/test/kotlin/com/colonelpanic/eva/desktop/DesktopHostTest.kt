package com.colonelpanic.eva.desktop

import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryCapabilities
import com.colonelpanic.eva.conversation.ProviderStatus
import com.colonelpanic.eva.conversation.TurnStatus
import com.colonelpanic.eva.conversation.prompt.Wording
import com.colonelpanic.eva.providers.CallIdentity
import com.colonelpanic.eva.providers.ConversationInput
import com.colonelpanic.eva.providers.ConversationProvider
import com.colonelpanic.eva.providers.ConversationSession
import com.colonelpanic.eva.providers.CorrelatedToolResult
import com.colonelpanic.eva.providers.ProviderEvent
import com.colonelpanic.eva.providers.ResponseRequest
import com.colonelpanic.eva.providers.SessionOpenRequest
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DesktopHostTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `the host starts from empty storage with the desktop prompt and tools`() =
        runBlocking {
            val paths = DesktopPaths(folder.root.resolve("config"), folder.root.resolve("data"))
            val lock = checkNotNull(paths.lock())
            DesktopHost(paths, Dispatchers.Default, lock, opener = { Opening.Opened }).use { host ->
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

    /** Connects, accepts every request, and never answers. */
    private class SilentProvider : ConversationProvider {
        override suspend fun open(request: SessionOpenRequest): ConversationSession =
            object : ConversationSession {
                override val connectionEpoch = "silent"
                override val events =
                    flow {
                        emit(ProviderEvent.Connected("silent", request.catalog.revision))
                        awaitCancellation()
                    }

                override suspend fun submit(input: ConversationInput) = Unit

                override suspend fun requestResponse(request: ResponseRequest) = Unit

                override suspend fun submitToolResult(result: CorrelatedToolResult) = Unit

                override suspend fun close() = Unit
            }
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun `shutdown interrupts a turn that outlives its deadline before giving up storage`() =
        runBlocking {
            val paths = DesktopPaths(folder.root.resolve("config"), folder.root.resolve("data"))
            val ui = newSingleThreadContext("test-ui")
            val host = DesktopHost(paths, ui, checkNotNull(paths.lock()), { Opening.Opened }) { SilentProvider() }
            val controller = host.controller
            controller.state.first { !it.isLoading }
            withContext(ui) { controller.newThread() }
            val threadId = checkNotNull(controller.state.first { it.threadId != null }.threadId)
            withContext(ui) { controller.connect("") }
            controller.state.first { it.providerStatus == ProviderStatus.CONNECTED }
            withContext(ui) { controller.submit("wait for an answer that never comes") }
            controller.working.first { it.isNotEmpty() }

            assertTrue(host.shutdown(timeoutMillis = 300))

            val reopened = checkNotNull(paths.lock()) { "A clean shutdown releases ownership" }
            JdbcJournal(paths.journal).use { journal ->
                assertEquals(listOf(TurnStatus.INTERRUPTED), JdbcConversationStore(journal).turns(threadId).map { it.status })
            }
            reopened.release()
            ui.close()
        }

    /** Answers every request by proposing to open one address, and nothing else. */
    private class OpeningProvider : ConversationProvider {
        override suspend fun open(request: SessionOpenRequest): ConversationSession {
            val events = Channel<ProviderEvent>(Channel.UNLIMITED)
            events.send(ProviderEvent.Connected("opening", request.catalog.revision))
            return object : ConversationSession {
                override val connectionEpoch = "opening"
                override val events = events.receiveAsFlow()
                private var input = ""

                override suspend fun submit(input: ConversationInput) {
                    this.input = input.id
                }

                override suspend fun requestResponse(request: ResponseRequest) {
                    val call = CallIdentity(connectionEpoch, "opening", input, input, "turn", requestCatalog(), "open-1")
                    events.send(
                        ProviderEvent.ToolCallReady(
                            call,
                            DesktopCapabilities.OPEN_URL,
                            buildJsonObject { put("url", "https://example.org") },
                        ),
                    )
                }

                private fun requestCatalog() = request.catalog.revision

                override suspend fun submitToolResult(result: CorrelatedToolResult) = Unit

                override suspend fun close() = Unit
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun `shutdown keeps storage while an action is still running and closes it once the action ends`() =
        runBlocking {
            val paths = DesktopPaths(folder.root.resolve("config"), folder.root.resolve("data"))
            val ui = newSingleThreadContext("test-ui")
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val opener =
                UrlOpener {
                    started.countDown()
                    release.await()
                    Opening.Opened
                }
            val host = DesktopHost(paths, ui, checkNotNull(paths.lock()), opener) { OpeningProvider() }
            val controller = host.controller
            controller.state.first { !it.isLoading }
            withContext(ui) { controller.newThread() }
            val threadId = checkNotNull(controller.state.first { it.threadId != null }.threadId)
            withContext(ui) { controller.connect("") }
            controller.state.first { it.providerStatus == ProviderStatus.CONNECTED }
            withContext(ui) { controller.submit("open the example page") }
            assertTrue(started.await(10, TimeUnit.SECONDS))

            assertFalse(host.shutdown(timeoutMillis = 300))
            assertNull("Storage stays owned while the action runs", paths.lock())

            release.countDown()
            assertTrue(host.shutdown(timeoutMillis = 10_000))
            val reopened = checkNotNull(paths.lock())
            JdbcJournal(paths.journal).use { journal ->
                assertEquals(listOf(TurnStatus.INTERRUPTED), JdbcConversationStore(journal).turns(threadId).map { it.status })
                // Interrupted mid-handoff, the journal must say it cannot tell, not lose the receipt.
                val receipt = JdbcInvocationRepository(journal).history().single()
                assertEquals(InvocationStatus.UNKNOWN, receipt.status)
            }
            reopened.release()
            ui.close()
        }
}
