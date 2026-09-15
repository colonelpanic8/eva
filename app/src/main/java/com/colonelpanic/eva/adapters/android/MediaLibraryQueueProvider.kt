package com.colonelpanic.eva.adapters.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class MediaLibraryApp(
    val packageName: String,
    val label: String,
    val serviceName: String,
)

interface MediaLibraryQueueClient {
    fun apps(): List<MediaLibraryApp>

    suspend fun queue(
        target: MediaLibraryApp,
        query: String,
    ): QueuedTrack?
}

/** One installed app reached through Media3's public search and playlist-editing contract. */
class MediaLibraryQueueProvider(
    private val target: MediaLibraryApp,
    private val client: MediaLibraryQueueClient,
) : QueueProvider {
    override val label: String = target.label

    override fun connected(): Boolean = true

    override suspend fun queue(query: String): QueuedTrack? = client.queue(target, query)
}

/** Discovers any player offering Media3 library search instead of keeping an EVA app allow-list. */
class AndroidMediaLibraryQueueClient(
    context: Context,
    private val settle: suspend () -> Unit = { delay(SETTLE_MILLIS) },
) : MediaLibraryQueueClient {
    private val app = context.applicationContext

    override fun apps(): List<MediaLibraryApp> {
        val packages = app.packageManager
        return packages
            .queryIntentServices(Intent(SERVICE_ACTION), 0)
            .mapNotNull { resolved ->
                val service = resolved.serviceInfo ?: return@mapNotNull null
                val label =
                    runCatching { packages.getApplicationLabel(service.applicationInfo).toString().trim() }
                        .getOrNull()
                        ?.takeIf(String::isNotBlank)
                        ?: service.packageName
                MediaLibraryApp(service.packageName, label, service.name)
            }.distinctBy { it.packageName }
    }

    override suspend fun queue(
        target: MediaLibraryApp,
        query: String,
    ): QueuedTrack? =
        try {
            withContext(Dispatchers.Main.immediate) {
                withTimeout(TIMEOUT_MILLIS) { queueWithinTimeout(target, query) }
            }
        } catch (error: TimeoutCancellationException) {
            throw IllegalStateException("${target.label} did not answer the queue request in time.", error)
        }

    private suspend fun queueWithinTimeout(
        target: MediaLibraryApp,
        query: String,
    ): QueuedTrack? {
        val opening =
            MediaBrowser
                .Builder(app, SessionToken(app, ComponentName(target.packageName, target.serviceName)))
                .buildAsync()
        val browser =
            try {
                opening.await()
            } catch (error: Exception) {
                throw IllegalStateException("${target.label} did not accept EVA as a media-library client.", error)
            }
        try {
            if (browser.search(query, null).await().resultCode != SessionResult.RESULT_SUCCESS) {
                error("${target.label} did not accept a library search.")
            }
            val result = browser.getSearchResult(query, 0, SEARCH_LIMIT, null).await()
            if (result.resultCode != SessionResult.RESULT_SUCCESS) {
                error("${target.label} did not return library search results.")
            }
            val item = result.value?.firstOrNull { it.mediaMetadata.isPlayable == true } ?: return null
            if (browser.currentMediaItem == null) {
                error("${target.label} is not playing anything, so there is nothing to queue onto. Play something first.")
            }
            if (!browser.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
                error("${target.label} does not let other Android apps change its queue.")
            }
            val before = browser.mediaItemCount
            browser.addMediaItem(browser.currentMediaItemIndex + 1, item)
            repeat(CONFIRM_ATTEMPTS) {
                settle()
                if (browser.mediaItemCount > before) {
                    return QueuedTrack(
                        title =
                            item.mediaMetadata.title
                                ?.toString()
                                ?.takeIf(String::isNotBlank) ?: query,
                        artists =
                            listOfNotNull(
                                item.mediaMetadata.artist
                                    ?.toString()
                                    ?.takeIf(String::isNotBlank),
                            ),
                    )
                }
            }
            error("${target.label} did not add the search result to its queue.")
        } finally {
            browser.release()
        }
    }

    private suspend fun <T> ListenableFuture<T>.await(): T =
        suspendCancellableCoroutine { continuation ->
            addListener(
                {
                    if (!continuation.isActive) return@addListener
                    try {
                        continuation.resume(get())
                    } catch (error: ExecutionException) {
                        continuation.resumeWithException(error.cause ?: error)
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                    }
                },
                DIRECT_EXECUTOR,
            )
            continuation.invokeOnCancellation { cancel(true) }
        }

    companion object {
        const val SERVICE_ACTION = "androidx.media3.session.MediaLibraryService"
        private const val SEARCH_LIMIT = 5
        private const val CONFIRM_ATTEMPTS = 3
        private const val SETTLE_MILLIS = 400L
        private const val TIMEOUT_MILLIS = 6_000L
        private val DIRECT_EXECUTOR = Executor(Runnable::run)
    }
}
