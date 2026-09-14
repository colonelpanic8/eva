package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Starts something by name without knowing anything about the app that plays it.
 *
 * Two general mechanisms exist and neither covers every app, so both are used in order.
 * `MediaBrowserService` is preferred: it starts a cold app, targets it exactly, needs no screen,
 * and leaves a session EVA can read back to say what actually started. An app that turns EVA
 * away, or has no media service at all, falls back to the documented play-from-search intent,
 * which any app can register and which needs EVA on screen to launch.
 */
class MediaPlayBackend(
    private val launcher: MediaLauncher,
    private val sessions: MediaSessionAccess,
    private val handoff: ExecutionBackend,
    private val settle: suspend () -> Unit = { delay(SETTLE_MILLIS) },
) : ExecutionBackend {
    /** The browser path needs no screen, so only a fallback to the intent can be unavailable. */
    override suspend fun unavailableReason(): String? = null

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
        val query = arguments.getValue("query")
        val requested = arguments["app"]?.trim()?.takeIf(String::isNotBlank)
        val target = withContext(Dispatchers.IO) { target(requested) }
        if (target == null) return handoff(arguments, null)
        return when (launcher.playOn(target, query)) {
            PlayDelivery.DELIVERED -> confirm(target, query)
            PlayDelivery.REFUSED -> handoff(arguments, "${target.label} did not accept EVA as a media client.")
            PlayDelivery.UNREACHABLE -> handoff(arguments, "${target.label} did not answer EVA's media service request.")
        }
    }

    /**
     * Package queries and session reads are binder calls, so the caller keeps them off the main thread.
     * A named app is resolved against installed media services. With no app named, an app already
     * holding a session that declares it takes searches is the one the user is using, so it is
     * preferred over a chooser; anything less certain is left to the intent.
     */
    private fun target(requested: String?): MediaApp? {
        val browsable = launcher.browsableApps()
        if (requested != null) return AppNames.best(browsable, requested, MediaApp::label, MediaApp::packageName)
        if (!sessions.observable()) return null
        val active = sessions.sessions().firstOrNull { it.canPlayFromSearch } ?: return null
        return browsable.firstOrNull { it.packageName == active.packageName }
    }

    private suspend fun confirm(
        target: MediaApp,
        query: String,
    ): ExecutionOutcome {
        settle()
        return withContext(Dispatchers.IO) {
            if (!sessions.observable()) {
                ExecutionOutcome(InvocationStatus.HANDED_OFF, MediaText.askedToPlay(target.label, query))
            } else {
                MediaText.confirmStarted(target.label, query, sessions.sessions().firstOrNull { it.packageName == target.packageName })
            }
        }
    }

    private suspend fun handoff(
        arguments: Map<String, String>,
        note: String?,
    ): ExecutionOutcome {
        val unavailable = handoff.unavailableReason()
        if (unavailable != null) {
            return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, listOfNotNull(note, unavailable).joinToString(" "))
        }
        return handoff.execute(arguments)
    }

    companion object {
        /** A cold app needs longer to report a track than a session already in front of one does. */
        const val SETTLE_MILLIS = 1_500L
    }
}
