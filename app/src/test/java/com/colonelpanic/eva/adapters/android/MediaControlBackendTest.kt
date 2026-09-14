package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaControlBackendTest {
    private val playing =
        MediaSnapshot(
            packageName = "com.spotify.music",
            appLabel = "Spotify",
            title = "Black Hole Sun",
            artist = "Soundgarden",
            playing = true,
            positionMillis = 64_000,
            durationMillis = 318_000,
        )

    private fun backend(
        access: MediaSessionAccess,
        operation: MediaControlBackend.Operation,
    ) = MediaControlBackend(access, operation, settle = {})

    @Test
    fun `pausing reports the session as it reads afterwards, not the command that was sent`() =
        runTest {
            val paused = playing.copy(playing = false)
            val access = FakeMediaSessions(mutableListOf(listOf(playing), listOf(playing), listOf(paused)))
            val outcome = backend(access, MediaControlBackend.Operation.CONTROL).execute(mapOf("action" to "toggle"))

            assertEquals(listOf("com.spotify.music" to MediaCommand.PAUSE), access.sent)
            assertEquals(InvocationStatus.COMPLETED, outcome.status)
            assertEquals("Spotify is paused on \"Black Hole Sun\" by Soundgarden, 1:04 of 5:18.", outcome.message)
        }

    @Test
    fun `a session that ignores the command is reported as unknown rather than done`() =
        runTest {
            val access = FakeMediaSessions(mutableListOf(listOf(playing)))
            val outcome = backend(access, MediaControlBackend.Operation.CONTROL).execute(mapOf("action" to "next"))

            assertEquals(InvocationStatus.UNKNOWN, outcome.status)
            assertTrue(outcome.message, outcome.message.contains("may not have changed"))
        }

    @Test
    fun `without notification access the media button is sent and the result is not claimed`() =
        runTest {
            val access = FakeMediaSessions(mutableListOf(listOf(playing)), granted = false)
            val outcome = backend(access, MediaControlBackend.Operation.CONTROL).execute(mapOf("action" to "pause"))

            assertEquals(listOf(MediaCommand.PAUSE), access.buttons)
            assertEquals(emptyList<Pair<String, MediaCommand>>(), access.sent)
            assertEquals(InvocationStatus.HANDED_OFF, outcome.status)
            assertTrue(outcome.message, outcome.message.contains("cannot see which app received it"))
        }

    @Test
    fun `now playing reports every session and the media volume`() =
        runTest {
            val access = FakeMediaSessions(mutableListOf(listOf(playing, MediaSnapshot("com.example.pods", "Pocket Casts", "Episode 12"))))
            val outcome = backend(access, MediaControlBackend.Operation.STATUS).execute(emptyMap())

            assertEquals(InvocationStatus.COMPLETED, outcome.status)
            assertEquals(
                "Spotify is playing \"Black Hole Sun\" by Soundgarden, 1:04 of 5:18.\n" +
                    "Pocket Casts is paused on \"Episode 12\".\n" +
                    "Media volume is 40%.",
                outcome.message,
            )
        }

    @Test
    fun `a volume change Android refuses is not reported as made`() =
        runTest {
            val access = FakeMediaSessions(mutableListOf(listOf(playing)), volumeReport = null)
            val outcome = backend(access, MediaControlBackend.Operation.VOLUME).execute(mapOf("action" to "set", "percent" to "20"))

            assertEquals(InvocationStatus.NOT_EXECUTED, outcome.status)
            assertEquals(MediaControlBackend.VOLUME_REFUSED, outcome.message)
        }
}
