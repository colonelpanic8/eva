package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.providers.spotify.SpotifyDevice
import com.colonelpanic.eva.providers.spotify.SpotifyQueueApi
import com.colonelpanic.eva.providers.spotify.SpotifyTrack
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeSpotifyQueueApi(
    var result: SpotifyTrack? =
        SpotifyTrack("spotify:track:1", "Black Hole Sun", listOf("Soundgarden"), "Superunknown"),
    var availableDevices: List<SpotifyDevice> = emptyList(),
) : SpotifyQueueApi {
    var queued: Pair<String, String?>? = null

    override suspend fun searchTrack(query: String) = result

    override suspend fun devices() = availableDevices

    override suspend fun queue(
        uri: String,
        deviceId: String?,
    ) {
        queued = uri to deviceId
    }
}

class SpotifyQueueProviderTest {
    private fun provider(api: SpotifyQueueApi) = SpotifyQueueProvider(api) { true }

    @Test
    fun `the device Spotify reports as active is where the track is queued`() =
        runTest {
            val api =
                FakeSpotifyQueueApi(
                    availableDevices =
                        listOf(
                            SpotifyDevice("idle", "Office", false, "Computer"),
                            SpotifyDevice("active", "Phone", true, "Smartphone"),
                        ),
                )

            val track = provider(api).queue("black hole sun")

            assertEquals("spotify:track:1" to "active", api.queued)
            assertEquals(QueuedTrack("Black Hole Sun", listOf("Soundgarden")), track)
        }

    @Test
    fun `a lone device is used even though Spotify calls none of them active`() =
        runTest {
            val api = FakeSpotifyQueueApi(availableDevices = listOf(SpotifyDevice("only", "Phone", false, "Smartphone")))

            provider(api).queue("anything")

            assertEquals("spotify:track:1" to "only", api.queued)
        }

    @Test
    fun `several idle devices leave the choice to Spotify rather than picking one`() =
        runTest {
            val api =
                FakeSpotifyQueueApi(
                    availableDevices =
                        listOf(
                            SpotifyDevice("one", "Office", false, "Computer"),
                            SpotifyDevice("two", "Phone", false, "Smartphone"),
                        ),
                )

            provider(api).queue("anything")

            assertEquals("spotify:track:1" to null, api.queued)
        }

    @Test
    fun `nothing is queued when the search matched no track`() =
        runTest {
            val api = FakeSpotifyQueueApi(result = null)

            assertNull(provider(api).queue("obscure"))
            assertNull(api.queued)
        }
}
