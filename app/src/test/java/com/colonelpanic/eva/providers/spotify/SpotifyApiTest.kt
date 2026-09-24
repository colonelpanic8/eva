package com.colonelpanic.eva.providers.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyApiTest {
    private val requests = mutableListOf<Request>()
    private var reply = "{}"

    private val api =
        SpotifyApi(
            { "token" },
            OkHttpClient
                .Builder()
                .addInterceptor(
                    Interceptor { chain ->
                        requests += chain.request()
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(if (chain.request().method == "PUT") 204 else 200)
                            .message("OK")
                            .body(reply.toResponseBody())
                            .build()
                    },
                ).build(),
            Dispatchers.Unconfined,
        )

    private val results =
        """{"artists":{"items":[{"uri":"spotify:artist:1","name":"Soundgarden"}]},
        "playlists":{"items":[null]},"albums":{"items":[]},
        "tracks":{"items":[{"uri":"spotify:track:1","name":"Black Hole Sun","artists":[{"name":"Soundgarden"}]}]}}"""

    @Test
    fun `an exactly named artist plays as a context, anything else plays the top track`() =
        runBlocking {
            reply = results
            assertEquals(SpotifyPlayable("spotify:artist:1", "Soundgarden", "artist"), api.searchPlayable("soundgarden"))
            assertEquals(
                SpotifyPlayable("spotify:track:1", "Black Hole Sun", "track", listOf("Soundgarden")),
                api.searchPlayable("black hole sun"),
            )
            assertEquals("artist,playlist,album,track", requests.last().url.queryParameter("type"))
        }

    @Test
    fun `play targets the chosen device with the track or the context`() =
        runBlocking {
            api.play(SpotifyPlayable("spotify:track:1", "Black Hole Sun", "track"), "phone")
            api.play(SpotifyPlayable("spotify:artist:1", "Soundgarden", "artist"), "phone")
            val bodies =
                requests.map { request ->
                    assertEquals("PUT", request.method)
                    assertEquals("phone", request.url.queryParameter("device_id"))
                    assertEquals("Bearer token", request.header("Authorization"))
                    Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                }
            assertEquals(listOf("""{"uris":["spotify:track:1"]}""", """{"context_uri":"spotify:artist:1"}"""), bodies)
        }
}
