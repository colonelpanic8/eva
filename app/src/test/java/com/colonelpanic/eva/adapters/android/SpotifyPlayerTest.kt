package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.providers.spotify.SpotifyDevice
import com.colonelpanic.eva.providers.spotify.SpotifyPlayable
import com.colonelpanic.eva.providers.spotify.SpotifyPlaybackApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

private class FakePlayback(
    var devices: List<SpotifyDevice>,
    private val found: SpotifyPlayable? = SpotifyPlayable("spotify:track:1", "Black Hole Sun", "track", listOf("Soundgarden")),
) : SpotifyPlaybackApi {
    val played = mutableListOf<Pair<String, String>>()

    override suspend fun searchPlayable(query: String) = found

    override suspend fun devices() = devices

    override suspend fun play(
        target: SpotifyPlayable,
        deviceId: String,
    ) {
        played += target.uri to deviceId
    }
}

class SpotifyPlayerTest {
    private val laptop = SpotifyDevice("laptop", "Laptop", isActive = false, type = "Computer")
    private val phone = SpotifyDevice("phone", "Pixel", isActive = false, type = "Smartphone")

    private fun player(
        api: FakePlayback,
        wake: () -> Unit = {},
    ) = SpotifyPlayer(api, { true }, { "Pixel" }, wake, pause = {})

    @Test
    fun `plays where the user is listening, else on this phone`() =
        runTest {
            val api = FakePlayback(listOf(laptop.copy(isActive = true), phone))
            assertEquals(RemotePlay("Black Hole Sun by Soundgarden", "Laptop"), player(api).play("black hole sun"))
            api.devices = listOf(laptop, phone)
            player(api).play("black hole sun")
            assertEquals(listOf("spotify:track:1" to "laptop", "spotify:track:1" to "phone"), api.played)
        }

    @Test
    fun `a stopped Spotify is woken once and its device is looked for again`() =
        runTest {
            val api = FakePlayback(emptyList())
            var woken = 0
            val played =
                player(api) {
                    woken++
                    api.devices = listOf(phone)
                }.play("black hole sun")
            assertEquals("Pixel", played!!.device)
            assertEquals(1, woken)
        }

    @Test
    fun `nothing found plays nothing and no device is an error`() =
        runTest {
            assertNull(player(FakePlayback(listOf(phone), found = null)).play("zzz"))
            val none = FakePlayback(emptyList())
            assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { player(none).play("black hole sun") } }
            assertEquals(emptyList<Pair<String, String>>(), none.played)
        }
}
