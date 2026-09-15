package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Starts something by name without naming an app; each app's own play action is the named form.
 *
 * A session the user is already in is best: `playFromSearch` on it needs no screen and can be
 * read back to say what started. Failing that, the play-from-search intent lets the phone
 * choose, which needs EVA on screen. Some apps answer that intent with a results screen rather
 * than playback, so once the intent has brought an app up, the session it now holds is asked to
 * play the words after all.
 */
class MediaPlayBackend(
    private val sessions: MediaSessionAccess,
    private val handoff: ExecutionBackend,
    private val settle: suspend () -> Unit = { delay(SETTLE_MILLIS) },
) : ExecutionBackend {
    /** The session path needs no screen, so only a fallback to the intent can be unavailable. */
    override suspend fun unavailableReason(): String? = null

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
        val query = arguments.getValue("query")
        val before = observed()
        val live = before.firstOrNull { it.canPlayFromSearch }
        if (live != null && withContext(Dispatchers.IO) { sessions.playFromSearch(live.packageName, query) }) {
            return confirm(live.appLabel, live.packageName, query, live)
        }
        return handoff(arguments, before, query)
    }

    /** Session reads are binder calls, so they stay off the main thread. Empty without the grant. */
    private suspend fun observed(): List<MediaSnapshot> =
        withContext(Dispatchers.IO) { if (sessions.observable()) sessions.sessions() else emptyList() }

    /**
     * Reads the session back until it shows something new or the wait runs out. An app fetching
     * a match over the network takes longer to report a track than one already playing.
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
        before: List<MediaSnapshot>,
        query: String,
    ): ExecutionOutcome {
        handoff.unavailableReason()?.let { return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, it) }
        val outcome = handoff.execute(arguments)
        if (outcome.status != InvocationStatus.HANDED_OFF) return outcome
        settle()
        val target = appeared(before, observed()) ?: return outcome
        val previous = before.firstOrNull { it.packageName == target.packageName }
        if (started(previous, target)) return MediaText.confirmStarted(target.appLabel, query, target)
        if (!target.canPlayFromSearch) return outcome
        if (!withContext(Dispatchers.IO) { sessions.playFromSearch(target.packageName, query) }) return outcome
        return confirm(target.appLabel, target.packageName, query, previous ?: target)
    }

    /**
     * The session the intent brought up: only one that was not there before can be attributed to
     * it, and it must take searches to be followed up.
     */
    private fun appeared(
        before: List<MediaSnapshot>,
        after: List<MediaSnapshot>,
    ): MediaSnapshot? =
        after.firstOrNull { candidate ->
            candidate.canPlayFromSearch &&
                before.none { it.packageName == candidate.packageName }
        }

    companion object {
        /** A cold app needs longer to report a track than a session already in front of one does. */
        const val SETTLE_MILLIS = 1_500L

        /** How many settle waits a confirmation spans before reporting whatever the session shows. */
        const val CONFIRM_READS = 3
    }
}
