package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.providers.spotify.SpotifyQueueApi

/**
 * Queues on Spotify through its Web API. Spotify refuses EVA as a media browser client, so the
 * account's own API is the only route to its queue, reached with a connection the user sets up.
 */
class SpotifyQueueProvider(
    private val api: SpotifyQueueApi,
    private val isConnected: () -> Boolean,
) : QueueProvider {
    override val label = "Spotify"

    override fun connected(): Boolean = isConnected()

    /**
     * Spotify queues onto a device rather than an app. The one it reports as active is where the
     * user is listening; failing that a lone device is unambiguous. With neither, Spotify is left
     * to choose and answers with its own reason when it cannot.
     */
    override suspend fun queue(query: String): QueuedTrack? {
        val track = api.searchTrack(query) ?: return null
        val devices = api.devices()
        api.queue(track.uri, devices.firstOrNull { it.isActive }?.id ?: devices.singleOrNull()?.id)
        return QueuedTrack(track.name, track.artists)
    }

    companion object {
        const val PACKAGE = "com.spotify.music"
    }
}
