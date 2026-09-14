package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.providers.spotify.SpotifyQueueApi
import java.io.IOException

class MediaQueueBackend(
    private val api: SpotifyQueueApi,
    private val connected: () -> Boolean,
) : ExecutionBackend {
    override suspend fun unavailableReason(): String? = null

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
        val requestedApp = arguments["app"]?.trim()?.takeIf(String::isNotBlank)
        if (requestedApp != null && !requestedApp.contains("spotify", ignoreCase = true)) {
            return notExecuted("EVA can only queue songs on Spotify right now. Nothing was queued.")
        }
        if (!connected()) {
            return notExecuted("Connect Spotify in EVA's settings to queue songs. Nothing was queued.")
        }
        val query = arguments.getValue("query")
        return try {
            val track = api.searchTrack(query) ?: return notExecuted("Spotify found nothing for \"$query\".")
            val devices = api.devices()
            val deviceId = devices.firstOrNull { it.isActive }?.id ?: devices.singleOrNull()?.id
            api.queue(track.uri, deviceId)
            val artists = track.artists.joinToString(", ").ifBlank { "an unknown artist" }
            ExecutionOutcome(
                InvocationStatus.COMPLETED,
                "Queued \"${track.name}\" by $artists on Spotify. It plays after the current track.",
            )
        } catch (error: IllegalStateException) {
            notExecuted(error.message ?: "Spotify did not accept the queue request.")
        } catch (_: IOException) {
            ExecutionOutcome(InvocationStatus.FAILED, "Spotify could not be reached.")
        }
    }

    private fun notExecuted(message: String) = ExecutionOutcome(InvocationStatus.NOT_EXECUTED, message)
}
