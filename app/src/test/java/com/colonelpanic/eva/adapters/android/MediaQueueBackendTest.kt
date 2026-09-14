package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.providers.spotify.SpotifyDevice
import com.colonelpanic.eva.providers.spotify.SpotifyQueueApi
import com.colonelpanic.eva.providers.spotify.SpotifyTrack
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

private class FakeSpotifyQueueApi : SpotifyQueueApi {
    var result: SpotifyTrack? = SpotifyTrack("spotify:track:1", "Black Hole Sun", listOf("Soundgarden", "Chris Cornell"), "Superunknown")
    var availableDevices =
        listOf(
            SpotifyDevice("idle", "Office", false, "Computer"),
            SpotifyDevice("active", "Phone", true, "Smartphone"),
        )
    var failure: Exception? = null
    var queued: Pair<String, String?>? = null

    override suspend fun searchTrack(query: String): SpotifyTrack? {
        failure?.let { throw it }
        return result
    }

    override suspend fun devices(): List<SpotifyDevice> {
        failure?.let { throw it }
        return availableDevices
    }

    override suspend fun queue(
        uri: String,
        deviceId: String?,
    ) {
        failure?.let { throw it }
        queued = uri to deviceId
    }
}

class MediaQueueBackendTest {
    private val api = FakeSpotifyQueueApi()

    @Test
    fun `no connection reports the settings hint without searching`() =
        runTest {
            val outcome = MediaQueueBackend(api, connected = { false }).execute(mapOf("query" to "anything"))

            assertEquals(InvocationStatus.NOT_EXECUTED, outcome.status)
            assertEquals("Connect Spotify in EVA's settings to queue songs. Nothing was queued.", outcome.message)
            assertNull(api.queued)
        }

    @Test
    fun `a named non-Spotify app is refused`() =
        runTest {
            val outcome =
                MediaQueueBackend(api, connected = { true })
                    .execute(mapOf("query" to "anything", "app" to "YouTube Music"))

            assertEquals(InvocationStatus.NOT_EXECUTED, outcome.status)
            assertEquals("EVA can only queue songs on Spotify right now. Nothing was queued.", outcome.message)
            assertNull(api.queued)
        }

    @Test
    fun `the top match is queued on the active device and named in the result`() =
        runTest {
            val outcome = MediaQueueBackend(api, connected = { true }).execute(mapOf("query" to "black hole sun"))

            assertEquals("spotify:track:1" to "active", api.queued)
            assertEquals(InvocationStatus.COMPLETED, outcome.status)
            assertEquals(
                "Queued \"Black Hole Sun\" by Soundgarden, Chris Cornell on Spotify. It plays after the current track.",
                outcome.message,
            )
        }

    @Test
    fun `Spotify playback restrictions pass through as not executed`() =
        runTest {
            val messages =
                listOf(
                    "Spotify only lets Premium accounts queue songs.",
                    "Spotify is not playing on any device, so there is nothing to queue onto. Play something first.",
                )
            for (message in messages) {
                api.failure = IllegalStateException(message)
                val outcome = MediaQueueBackend(api, connected = { true }).execute(mapOf("query" to "anything"))
                assertEquals(InvocationStatus.NOT_EXECUTED, outcome.status)
                assertEquals(message, outcome.message)
            }
        }

    @Test
    fun `a network failure is reported as failed`() =
        runTest {
            api.failure = IOException("offline")

            val outcome = MediaQueueBackend(api, connected = { true }).execute(mapOf("query" to "anything"))

            assertEquals(InvocationStatus.FAILED, outcome.status)
            assertEquals("Spotify could not be reached.", outcome.message)
        }
}
