package com.colonelpanic.eva.adapters.android

/** What an app took into its queue, in the words EVA reports back. */
data class QueuedTrack(
    val title: String,
    val artists: List<String>,
)

/**
 * One app EVA can add a track to the queue of.
 *
 * Unlike playing, pausing, and skipping, this cannot be done for apps in general. Android's
 * transport controls carry play, skip, seek, and play-from-search but nothing that appends to a
 * queue, and the queue a session publishes is readable only. Queueing therefore needs a route the
 * app itself offers, and each such route is a provider here.
 *
 * An app EVA can play through but not queue on is the normal case, not a broken one: it simply has
 * no provider, and the backend says so instead of failing.
 */
interface QueueProvider {
    /** The name the user would say, and the name EVA uses when it reports what it did. */
    val label: String

    /** False until the user finishes connecting it, which is a setup step rather than a refusal. */
    fun connected(): Boolean

    /**
     * Queues the best match for the words, or null when the app matched nothing. A reason the app
     * itself gave is an `IllegalStateException` carrying a message fit to read aloud; an
     * unreachable app is an `IOException`.
     */
    suspend fun queue(query: String): QueuedTrack?
}
