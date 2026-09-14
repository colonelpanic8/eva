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
 * Three general mechanisms exist and none covers every app, so they are used in order. A session
 * the app already holds is best: `playFromSearch` on it reaches apps that allow-list their media
 * browser service, needs no screen, and can be read back to say what started. With no session,
 * `MediaBrowserService` starts a cold app and hands back a session that takes the same request.
 * An app that turns EVA away there, or has no media service at all, falls back to the documented
 * play-from-search intent, which any app can register and which needs EVA on screen to launch.
 * Some apps answer that intent with a results screen rather than playback, so once the intent has
 * brought the app up, the session it now holds is asked to play the words after all.
 */
class MediaPlayBackend(
    private val launcher: MediaLauncher,
    private val sessions: MediaSessionAccess,
    private val handoff: ExecutionBackend,
    private val settle: suspend () -> Unit = { delay(SETTLE_MILLIS) },
) : ExecutionBackend {
    /** The session and browser paths need no screen, so only a fallback to the intent can be unavailable. */
    override suspend fun unavailableReason(): String? = null

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
        val query = arguments.getValue("query")
        val requested = arguments["app"]?.trim()?.takeIf(String::isNotBlank)
        val before = observed()
        val live = liveTarget(before, requested)
        if (live != null && withContext(Dispatchers.IO) { sessions.playFromSearch(live.packageName, query) }) {
            return confirm(live.appLabel, live.packageName, query, live)
        }
        val target = withContext(Dispatchers.IO) { browsable(requested) }
        var note: String? = null
        if (target != null) {
            when (launcher.playOn(target, query)) {
                PlayDelivery.DELIVERED -> return confirm(target.label, target.packageName, query, null)
                PlayDelivery.REFUSED -> note = "${target.label} did not accept EVA as a media client."
                PlayDelivery.UNREACHABLE -> note = "${target.label} did not answer EVA's media service request."
            }
        }
        return handoff(arguments, note, before, requested, query)
    }

    /** Session reads are binder calls, so they stay off the main thread. Empty without the grant. */
    private suspend fun observed(): List<MediaSnapshot> =
        withContext(Dispatchers.IO) { if (sessions.observable()) sessions.sessions() else emptyList() }

    /**
     * A session that declares it takes searches is asked directly. Named, it must be that app's;
     * unnamed, it is the app the user is already using, so it is preferred over a chooser.
     */
    private fun liveTarget(
        sessions: List<MediaSnapshot>,
        requested: String?,
    ): MediaSnapshot? =
        if (requested == null) {
            sessions.firstOrNull { it.canPlayFromSearch }
        } else {
            MediaRouting.select(sessions, requested)?.takeIf { it.canPlayFromSearch }
        }

    /** Only a named app is started cold through its browser service; unnamed, the intent's chooser applies. */
    private fun browsable(requested: String?): MediaApp? {
        if (requested == null) return null
        return AppNames.best(launcher.browsableApps(), requested, MediaApp::label, MediaApp::packageName)
    }

    /**
     * Reads the session back until it shows something new or the wait runs out. A cold app, or one
     * fetching a match over the network, takes longer to report a track than one already playing.
     */
    private suspend fun confirm(
        label: String,
        packageName: String,
        query: String,
        previous: MediaSnapshot?,
    ): ExecutionOutcome {
        var after: MediaSnapshot? = null
        repeat(CONFIRM_READS) {
            settle()
            if (!sessions.observable()) {
                return ExecutionOutcome(InvocationStatus.HANDED_OFF, MediaText.askedToPlay(label, query))
            }
            after = withContext(Dispatchers.IO) { sessions.sessions().firstOrNull { it.packageName == packageName } }
            if (after?.let { started(previous, it) } == true) return MediaText.confirmStarted(label, query, after)
        }
        return MediaText.confirmStarted(label, query, after)
    }

    /** Playing the same track the session already showed is not evidence the request was matched. */
    private fun started(
        previous: MediaSnapshot?,
        after: MediaSnapshot,
    ): Boolean = after.playing && (previous == null || !previous.playing || previous.title != after.title)

    private suspend fun handoff(
        arguments: Map<String, String>,
        note: String?,
        before: List<MediaSnapshot>,
        requested: String?,
        query: String,
    ): ExecutionOutcome {
        val unavailable = handoff.unavailableReason()
        if (unavailable != null) {
            return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, listOfNotNull(note, unavailable).joinToString(" "))
        }
        val outcome = handoff.execute(arguments)
        if (outcome.status != InvocationStatus.HANDED_OFF) return outcome
        settle()
        val after = observed()
        val target = appeared(before, after, requested) ?: return outcome
        val previous = before.firstOrNull { it.packageName == target.packageName }
        if (started(previous, target)) return MediaText.confirmStarted(target.appLabel, query, target)
        if (!target.canPlayFromSearch) return outcome
        if (!withContext(Dispatchers.IO) { sessions.playFromSearch(target.packageName, query) }) return outcome
        return confirm(target.appLabel, target.packageName, query, previous ?: target)
    }

    /**
     * The session the intent brought up. Named, it is whichever session now carries that name,
     * even one that existed before without taking searches; unnamed, only a session that was not
     * there before can be attributed to the intent, and it must take searches to be followed up.
     */
    private fun appeared(
        before: List<MediaSnapshot>,
        after: List<MediaSnapshot>,
        requested: String?,
    ): MediaSnapshot? {
        if (requested != null) return MediaRouting.select(after, requested)
        return after.firstOrNull { candidate -> candidate.canPlayFromSearch && before.none { it.packageName == candidate.packageName } }
    }

    companion object {
        /** A cold app needs longer to report a track than a session already in front of one does. */
        const val SETTLE_MILLIS = 1_500L

        /** How many settle waits a confirmation spans before reporting whatever the session shows. */
        const val CONFIRM_READS = 3
    }
}
