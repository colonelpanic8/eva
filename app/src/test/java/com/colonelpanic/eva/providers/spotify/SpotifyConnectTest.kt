package com.colonelpanic.eva.providers.spotify

import android.net.Uri
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class FakeSpotifyLogin : SpotifyLoginClient {
    var exchange: Triple<String, String, String>? = null
    val tokens = SpotifyTokens("access", "refresh", 1000, "scope")
    val profile = SpotifyProfile("Ivan", "premium")

    override suspend fun exchange(
        clientId: String,
        code: String,
        verifier: String,
    ): SpotifyTokens {
        exchange = Triple(clientId, code, verifier)
        return tokens
    }

    override suspend fun profile(accessToken: String): SpotifyProfile = profile
}

@RunWith(RobolectricTestRunner::class)
class SpotifyConnectTest {
    private val login = FakeSpotifyLogin()
    private var saved: Pair<SpotifyTokens, SpotifyProfile>? = null

    private fun connect() =
        SpotifyConnect(
            login = login,
            authorizationUrl = { _, challenge, state -> "https://accounts.test?challenge=$challenge&state=$state" },
            save = { tokens, profile -> saved = tokens to profile },
            verifier = { "verifier" },
            stateValue = { "expected-state" },
        )

    @Test
    fun `complete rejects a redirect with a mismatched state`() =
        runTest {
            val connect = connect()
            connect.begin("client-id")

            connect.complete(Uri.parse("eva://spotify?code=code&state=other-state"))

            assertEquals(SpotifyConnectState.Failed(SpotifyConnect.MISMATCHED_STATE), connect.state.value)
            assertNull(login.exchange)
            assertNull(saved)
        }

    @Test
    fun `complete exchanges and saves a redirect with the matching state`() =
        runTest {
            val connect = connect()
            connect.begin("client-id")

            connect.complete(Uri.parse("eva://spotify?code=approved-code&state=expected-state"))

            assertEquals(Triple("client-id", "approved-code", "verifier"), login.exchange)
            assertEquals(login.tokens to login.profile, saved)
            assertEquals(SpotifyConnectState.Idle, connect.state.value)
        }
}
