package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

private class FakeQueueProvider(
    override val label: String,
    private val connected: Boolean = true,
    private val result: QueuedTrack? = QueuedTrack("Black Hole Sun", listOf("Soundgarden", "Chris Cornell")),
    private val failure: Exception? = null,
) : QueueProvider {
    var queued: String? = null

    override fun matches(app: String) = app.contains(label, ignoreCase = true)

    override fun connected() = connected

    override suspend fun queue(query: String): QueuedTrack? {
        failure?.let { throw it }
        queued = query
        return result
    }
}

class MediaQueueBackendTest {
    private val spotify = FakeQueueProvider("Spotify")

    @Test
    fun `the top match is queued on the only connected app and both are named in the result`() =
        runTest {
            val outcome = MediaQueueBackend(listOf(spotify)).execute(mapOf("query" to "black hole sun"))

            assertEquals("black hole sun", spotify.queued)
            assertEquals(InvocationStatus.COMPLETED, outcome.status)
            assertEquals(
                "Queued \"Black Hole Sun\" by Soundgarden, Chris Cornell on Spotify. It plays after the current track.",
                outcome.message,
            )
        }

    @Test
    fun `an app no provider covers is told what EVA can queue on instead`() =
        runTest {
            val outcome =
                MediaQueueBackend(listOf(spotify)).execute(mapOf("query" to "anything", "app" to "YouTube Music"))

            assertEquals(InvocationStatus.NOT_EXECUTED, outcome.status)
            assertEquals("EVA cannot queue on YouTube Music. It can queue on Spotify. Nothing was queued.", outcome.message)
            assertNull(spotify.queued)
        }

    @Test
    fun `an unconnected provider is a setup hint naming it rather than a refusal to queue`() =
        runTest {
            val offline = FakeQueueProvider("Spotify", connected = false)
            val outcome = MediaQueueBackend(listOf(offline)).execute(mapOf("query" to "anything"))

            assertEquals(InvocationStatus.NOT_EXECUTED, outcome.status)
            assertEquals("Connect Spotify in EVA's settings to queue songs. Nothing was queued.", outcome.message)
            assertNull(offline.queued)
        }

    @Test
    fun `with two connected apps and none named EVA asks which rather than choosing`() =
        runTest {
            val other = FakeQueueProvider("Tidal")
            val outcome = MediaQueueBackend(listOf(spotify, other)).execute(mapOf("query" to "anything"))

            assertEquals(InvocationStatus.NOT_EXECUTED, outcome.status)
            assertEquals("More than one app can queue: Spotify, Tidal. Say which one. Nothing was queued.", outcome.message)
            assertNull(spotify.queued)
            assertNull(other.queued)
        }

    @Test
    fun `a named app is queued even when another is also connected`() =
        runTest {
            val other = FakeQueueProvider("Tidal")
            val outcome =
                MediaQueueBackend(listOf(spotify, other)).execute(mapOf("query" to "anything", "app" to "tidal"))

            assertEquals(InvocationStatus.COMPLETED, outcome.status)
            assertEquals("anything", other.queued)
            assertNull(spotify.queued)
        }

    @Test
    fun `a reason the app gave is passed through as not executed`() =
        runTest {
            val message = "Spotify only lets Premium accounts queue songs."
            val refusing = FakeQueueProvider("Spotify", failure = IllegalStateException(message))

            val outcome = MediaQueueBackend(listOf(refusing)).execute(mapOf("query" to "anything"))

            assertEquals(InvocationStatus.NOT_EXECUTED, outcome.status)
            assertEquals(message, outcome.message)
        }

    @Test
    fun `an unreachable app is failed and named`() =
        runTest {
            val offline = FakeQueueProvider("Spotify", failure = IOException("offline"))

            val outcome = MediaQueueBackend(listOf(offline)).execute(mapOf("query" to "anything"))

            assertEquals(InvocationStatus.FAILED, outcome.status)
            assertEquals("Spotify could not be reached.", outcome.message)
        }

    @Test
    fun `words the app matched nothing for are reported without claiming a queue`() =
        runTest {
            val empty = FakeQueueProvider("Spotify", result = null)

            val outcome = MediaQueueBackend(listOf(empty)).execute(mapOf("query" to "obscure"))

            assertEquals(InvocationStatus.NOT_EXECUTED, outcome.status)
            assertEquals("Spotify found nothing for \"obscure\". Nothing was queued.", outcome.message)
        }
}
