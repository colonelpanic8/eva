package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeLauncher(
    private val apps: List<MediaApp>,
    private val delivery: PlayDelivery = PlayDelivery.DELIVERED,
) : MediaLauncher {
    var asked: Pair<MediaApp, String>? = null

    override fun browsableApps() = apps

    override suspend fun playOn(
        target: MediaApp,
        query: String,
    ): PlayDelivery {
        asked = target to query
        return delivery
    }
}

private class FakeHandoff(
    private val unavailable: String? = null,
) : ExecutionBackend {
    var executed: Map<String, String>? = null

    override suspend fun unavailableReason() = unavailable

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
        executed = arguments
        return ExecutionOutcome(InvocationStatus.HANDED_OFF, "Asked a music app to play that.")
    }
}

class MediaPlayBackendTest {
    private val spotifyApp = MediaApp("com.spotify.music", "Spotify", "com.spotify.MediaService")
    private val podcastApp = MediaApp("com.example.pods", "Pocket Casts", "com.example.MediaService")

    private fun backend(
        launcher: MediaLauncher,
        sessions: MediaSessionAccess,
        handoff: ExecutionBackend,
    ) = MediaPlayBackend(launcher, sessions, handoff, settle = {})

    @Test
    fun `a named app is asked through its media service and answered with what actually started`() =
        runTest {
            val started =
                MediaSnapshot("com.spotify.music", "Spotify", title = "Black Hole Sun", artist = "Soundgarden", playing = true)
            val launcher = FakeLauncher(listOf(podcastApp, spotifyApp))
            val handoff = FakeHandoff()
            val outcome =
                backend(launcher, FakeMediaSessions(mutableListOf(listOf(started))), handoff)
                    .execute(mapOf("query" to "black hole sun", "app" to "spotify"))

            assertEquals(spotifyApp to "black hole sun", launcher.asked)
            assertNull(handoff.executed)
            assertEquals(InvocationStatus.COMPLETED, outcome.status)
            assertEquals(
                "Spotify is playing \"Black Hole Sun\" by Soundgarden. That is Spotify's match for \"black hole sun\".",
                outcome.message,
            )
        }

    @Test
    fun `an app that turns EVA away falls back to the intent instead of failing`() =
        runTest {
            val launcher = FakeLauncher(listOf(spotifyApp), delivery = PlayDelivery.REFUSED)
            val handoff = FakeHandoff()
            val arguments = mapOf("query" to "black hole sun", "app" to "spotify")
            val outcome = backend(launcher, FakeMediaSessions(mutableListOf(emptyList())), handoff).execute(arguments)

            assertEquals(arguments, handoff.executed)
            assertEquals(InvocationStatus.HANDED_OFF, outcome.status)
        }

    @Test
    fun `a refusal with no screen to open an app says both why it failed and what blocked the fallback`() =
        runTest {
            val launcher = FakeLauncher(listOf(spotifyApp), delivery = PlayDelivery.REFUSED)
            val handoff = FakeHandoff(unavailable = "Open EVA before sending this request.")
            val outcome =
                backend(launcher, FakeMediaSessions(mutableListOf(emptyList())), handoff)
                    .execute(mapOf("query" to "anything", "app" to "spotify"))

            assertEquals(InvocationStatus.NOT_EXECUTED, outcome.status)
            assertEquals(
                "Spotify did not accept EVA as a media client. Open EVA before sending this request.",
                outcome.message,
            )
        }

    @Test
    fun `with no app named the session already taking searches is preferred over a chooser`() =
        runTest {
            val active = MediaSnapshot("com.spotify.music", "Spotify", playing = false, canPlayFromSearch = true)
            val launcher = FakeLauncher(listOf(spotifyApp, podcastApp))
            val handoff = FakeHandoff()
            backend(launcher, FakeMediaSessions(mutableListOf(listOf(active))), handoff).execute(mapOf("query" to "anything"))

            assertEquals(spotifyApp to "anything", launcher.asked)
            assertNull(handoff.executed)
        }

    @Test
    fun `with no app named and no session taking searches the intent chooses`() =
        runTest {
            val active = MediaSnapshot("com.spotify.music", "Spotify", playing = true)
            val launcher = FakeLauncher(listOf(spotifyApp))
            val handoff = FakeHandoff()
            backend(launcher, FakeMediaSessions(mutableListOf(listOf(active))), handoff).execute(mapOf("query" to "anything"))

            assertNull(launcher.asked)
            assertEquals(mapOf("query" to "anything"), handoff.executed)
        }

    @Test
    fun `an app that takes the request but plays nothing is not reported as playing`() =
        runTest {
            val launcher = FakeLauncher(listOf(spotifyApp))
            val outcome =
                backend(launcher, FakeMediaSessions(mutableListOf(emptyList())), FakeHandoff())
                    .execute(mapOf("query" to "obscure", "app" to "spotify"))

            assertEquals(InvocationStatus.UNKNOWN, outcome.status)
            assertTrue(outcome.message, outcome.message.contains("not publishing any playback"))
        }
}
