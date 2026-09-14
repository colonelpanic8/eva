package com.colonelpanic.eva.adapters.android

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeMediaLibraryQueueClient : MediaLibraryQueueClient {
    var queued: Pair<MediaLibraryApp, String>? = null

    override fun apps(): List<MediaLibraryApp> = emptyList()

    override suspend fun queue(
        target: MediaLibraryApp,
        query: String,
    ): QueuedTrack {
        queued = target to query
        return QueuedTrack("Black Hole Sun", listOf("Soundgarden"))
    }
}

class MediaLibraryQueueProviderTest {
    private val app = MediaLibraryApp("org.jellyfin.mobile", "Jellyfin", "org.jellyfin.mobile.LibraryService")
    private val client = FakeMediaLibraryQueueClient()
    private val provider = MediaLibraryQueueProvider(app, client)

    @Test
    fun `an installed library app is matched by label or package name`() {
        assertTrue(provider.matches("Jellyfin"))
        assertTrue(provider.matches("jelly"))
        assertFalse(provider.matches("YouTube Music"))
    }

    @Test
    fun `queue delegates the search to the app's public media library`() =
        runTest {
            val result = provider.queue("black hole sun")

            assertEquals(app to "black hole sun", client.queued)
            assertEquals(QueuedTrack("Black Hole Sun", listOf("Soundgarden")), result)
        }
}
