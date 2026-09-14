package com.colonelpanic.eva.data

import com.colonelpanic.eva.providers.spotify.SpotifyProfile
import com.colonelpanic.eva.providers.spotify.SpotifyTokenRefresher
import com.colonelpanic.eva.providers.spotify.SpotifyTokens
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeSpotifyAccountStorage : SpotifyAccountStorage {
    var clientId: String? = "client-id"
    var account: String? = null

    override fun clientId(): String? = clientId

    override fun saveClientId(value: String?) {
        clientId = value
    }

    override fun account(): String? = account

    override fun saveAccount(value: String) {
        account = value
    }

    override fun clearAccount() {
        account = null
    }
}

private class FakeSpotifyRefresher : SpotifyTokenRefresher {
    var refreshes = 0

    override suspend fun refresh(
        clientId: String,
        previous: SpotifyTokens,
    ): SpotifyTokens {
        refreshes++
        return previous.copy(accessToken = "fresh", expiresAt = 2_000_000)
    }
}

class SpotifyAccountStoreTest {
    @Test
    fun `accessToken refreshes only when the token is within two minutes of expiry`() =
        runTest {
            val storage = FakeSpotifyAccountStorage()
            val refresher = FakeSpotifyRefresher()
            var now = 0L
            val store = SpotifyAccountStore(storage, refresher, now = { now })
            store.save(
                SpotifyTokens("original", "refresh", expiresAt = 1_000_000, scope = null),
                SpotifyProfile("Ivan", "premium"),
            )

            assertEquals("original", store.accessToken())
            assertEquals(0, refresher.refreshes)

            now = 900_000
            assertEquals("fresh", store.accessToken())
            assertEquals(1, refresher.refreshes)
        }
}
