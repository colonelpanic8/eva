package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.providers.spotify.SpotifyDevice
import com.colonelpanic.eva.providers.spotify.SpotifyPlaybackApi
import kotlinx.coroutines.delay

/** Starts playback through an app's own account service, which needs no screen and works on a locked phone. */
interface RemotePlayer {
    fun connected(): Boolean

    /** What started and where, or null when the service found nothing for the words. */
    suspend fun play(query: String): RemotePlay?
}

data class RemotePlay(
    val title: String,
    val device: String,
)

/**
 * Plays on the device Spotify reports as active, else this phone, else the only device. With
 * none, Spotify is not running here: a media-button press wakes it without a screen, and its
 * device is looked for again for a few seconds.
 */
class SpotifyPlayer(
    private val api: SpotifyPlaybackApi,
    private val isConnected: () -> Boolean,
    private val phoneName: () -> String,
    private val wake: () -> Unit,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : RemotePlayer {
    override fun connected(): Boolean = isConnected()

    override suspend fun play(query: String): RemotePlay? {
        val target = api.searchPlayable(query) ?: return null
        val device = device() ?: error("Spotify has no device to play on. Open Spotify on this phone once, then ask again.")
        api.play(target, device.id)
        val title = if (target.artists.isEmpty()) target.name else "${target.name} by ${target.artists.joinToString(", ")}"
        return RemotePlay(title, device.name)
    }

    private suspend fun device(): SpotifyDevice? {
        repeat(WAKE_POLLS) { attempt ->
            val devices = api.devices()
            (
                devices.firstOrNull { it.isActive }
                    ?: devices.firstOrNull { it.type.equals("Smartphone", ignoreCase = true) && it.name == phoneName() }
                    ?: devices.singleOrNull()
            )?.let { return it }
            if (attempt == 0) wake()
            pause(WAKE_POLL_MILLIS)
        }
        return null
    }

    companion object {
        const val WAKE_POLLS = 6
        const val WAKE_POLL_MILLIS = 1_000L
    }
}
