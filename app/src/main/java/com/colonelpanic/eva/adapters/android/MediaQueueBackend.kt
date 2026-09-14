package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import java.io.IOException

/**
 * Adds a track to an app's queue through whichever provider covers that app.
 *
 * Every refusal here names what EVA can queue on instead, because an app with no provider is the
 * expected case rather than an error, and the user cannot tell the two apart from "no".
 */
class MediaQueueBackend(
    private val providers: List<QueueProvider>,
) : ExecutionBackend {
    /** A provider talks to a service or a session, so unlike an intent this needs no screen. */
    override suspend fun unavailableReason(): String? = null

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
        val requested = arguments["app"]?.trim()?.takeIf(String::isNotBlank)
        val provider =
            when (val choice = choose(requested)) {
                is Choice.Refused -> return notExecuted(choice.message)
                is Choice.Chosen -> choice.provider
            }
        val query = arguments.getValue("query")
        return try {
            val track =
                provider.queue(query)
                    ?: return notExecuted("${provider.label} found nothing for \"$query\". Nothing was queued.")
            val artists = track.artists.joinToString(", ").ifBlank { "an unknown artist" }
            ExecutionOutcome(
                InvocationStatus.COMPLETED,
                "Queued \"${track.title}\" by $artists on ${provider.label}. It plays after the current track.",
            )
        } catch (error: IllegalStateException) {
            notExecuted(error.message ?: "${provider.label} did not accept the queue request.")
        } catch (_: IOException) {
            ExecutionOutcome(InvocationStatus.FAILED, "${provider.label} could not be reached.")
        }
    }

    private sealed interface Choice {
        data class Chosen(
            val provider: QueueProvider,
        ) : Choice

        data class Refused(
            val message: String,
        ) : Choice
    }

    /**
     * A named app has to be one EVA has a provider for, and a connected one. Unnamed, the single
     * connected provider is the only unambiguous choice; queueing onto the wrong service is worse
     * than asking, so more than one is a question rather than a guess.
     */
    private fun choose(requested: String?): Choice {
        if (requested != null) {
            val provider =
                providers.firstOrNull { it.matches(requested) }
                    ?: return Choice.Refused("EVA cannot queue on $requested. ${capabilities()} Nothing was queued.")
            if (!provider.connected()) return Choice.Refused(connect(listOf(provider)))
            return Choice.Chosen(provider)
        }
        val connected = providers.filter(QueueProvider::connected)
        return when (connected.size) {
            1 -> {
                Choice.Chosen(connected.first())
            }

            0 -> {
                Choice.Refused(connect(providers))
            }

            else -> {
                val labels = connected.joinToString(", ", transform = QueueProvider::label)
                Choice.Refused("More than one app can queue: $labels. Say which one. Nothing was queued.")
            }
        }
    }

    private fun capabilities(): String =
        if (providers.isEmpty()) "EVA cannot queue on anything yet." else "It can queue on ${names(providers)}."

    private fun connect(missing: List<QueueProvider>): String =
        if (missing.isEmpty()) {
            "EVA has no app it can queue on. Nothing was queued."
        } else {
            "Connect ${names(missing)} in EVA's settings to queue songs. Nothing was queued."
        }

    /** "Spotify", "Spotify or YouTube Music", "Spotify, Tidal, or YouTube Music". */
    private fun names(providers: List<QueueProvider>): String {
        val labels = providers.map(QueueProvider::label)
        if (labels.size <= 1) return labels.joinToString()
        return "${labels.dropLast(1).joinToString(", ")}${if (labels.size > 2) "," else ""} or ${labels.last()}"
    }

    private fun notExecuted(message: String) = ExecutionOutcome(InvocationStatus.NOT_EXECUTED, message)
}
