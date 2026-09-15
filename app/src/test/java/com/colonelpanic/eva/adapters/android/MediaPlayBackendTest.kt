package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    private fun backend(
        sessions: MediaSessionAccess,
        handoff: ExecutionBackend,
    ) = MediaPlayBackend(sessions, handoff, settle = {})

    @Test
    fun `the session already taking searches is asked instead of a chooser`() =
        runTest {
            val active = MediaSnapshot("com.spotify.music", "Spotify", playing = false, canPlayFromSearch = true)
            val handoff = FakeHandoff()
            val sessions = FakeMediaSessions(mutableListOf(listOf(active)))
            backend(sessions, handoff).execute(mapOf("query" to "anything"))

            assertEquals(listOf("com.spotify.music" to "anything"), sessions.searches)
            assertNull(handoff.executed)
        }

    @Test
    fun `an intent that only brought the app up is followed by asking its new session to play`() =
        runTest {
            val opened = MediaSnapshot("com.spotify.music", "Spotify", canPlayFromSearch = true)
            val started = opened.copy(title = "Black Hole Sun", artist = "Soundgarden", playing = true)
            val handoff = FakeHandoff()
            val sessions = FakeMediaSessions(mutableListOf(emptyList(), listOf(opened), listOf(started)))
            val outcome = backend(sessions, handoff).execute(mapOf("query" to "black hole sun"))

            assertEquals(mapOf("query" to "black hole sun"), handoff.executed)
            assertEquals(listOf("com.spotify.music" to "black hole sun"), sessions.searches)
            assertEquals(InvocationStatus.COMPLETED, outcome.status)
            assertTrue(outcome.message, outcome.message.startsWith("Spotify is playing \"Black Hole Sun\""))
        }

    @Test
    fun `an intent that already started playback is not asked a second time`() =
        runTest {
            val started =
                MediaSnapshot("com.spotify.music", "Spotify", title = "Black Hole Sun", playing = true, canPlayFromSearch = true)
            val sessions = FakeMediaSessions(mutableListOf(emptyList(), listOf(started)))
            val outcome = backend(sessions, FakeHandoff()).execute(mapOf("query" to "black hole sun"))

            assertTrue(sessions.searches.isEmpty())
            assertEquals(InvocationStatus.COMPLETED, outcome.status)
        }

    @Test
    fun `with no session taking searches the intent chooses`() =
        runTest {
            val active = MediaSnapshot("com.spotify.music", "Spotify", playing = true)
            val handoff = FakeHandoff()
            backend(FakeMediaSessions(mutableListOf(listOf(active))), handoff).execute(mapOf("query" to "anything"))

            assertEquals(mapOf("query" to "anything"), handoff.executed)
        }
}
