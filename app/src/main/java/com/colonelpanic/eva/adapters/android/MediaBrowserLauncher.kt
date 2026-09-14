package com.colonelpanic.eva.adapters.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** How far a request to play something by name got before EVA stopped being able to follow it. */
enum class PlayDelivery {
    /** The app accepted EVA as a client and was asked to play the words. */
    DELIVERED,

    /** The app has a media service but turned EVA away, which allow-listed apps do to unknown callers. */
    REFUSED,

    /** Nothing answered in time, so the request was never made. */
    UNREACHABLE,
}

/**
 * Starting content by name in an app that is not already playing. A media session cannot do it:
 * a session exists only once an app is running, so `play X on Spotify` from cold has nothing to
 * talk to. `MediaBrowserService` is the platform's answer, and the same one Android Auto and
 * Wear OS use — connecting starts the app's media service, and the session it hands back takes
 * `playFromSearch`. Unlike an intent this needs no screen, so it works from a locked phone.
 *
 * The app decides in `onGetRoot` whether to accept the caller. Many allow-list Android Auto,
 * Wear, and Google's assistant by package and signature, so a refusal is expected rather than a
 * bug, and the caller falls back to the documented play-from-search intent.
 */
interface MediaLauncher {
    fun browsableApps(): List<MediaApp>

    suspend fun playOn(
        target: MediaApp,
        query: String,
    ): PlayDelivery
}

class AndroidMediaLauncher(
    context: Context,
    private val hold: suspend () -> Unit = { delay(HOLD_MILLIS) },
) : MediaLauncher {
    private val app = context.applicationContext

    override fun browsableApps(): List<MediaApp> {
        val packages = app.packageManager
        return packages
            .queryIntentServices(Intent(SERVICE_ACTION), 0)
            .mapNotNull { resolved ->
                val service = resolved.serviceInfo ?: return@mapNotNull null
                val label =
                    runCatching { packages.getApplicationLabel(service.applicationInfo).toString().trim() }
                        .getOrNull()
                        ?.takeIf(String::isNotBlank)
                MediaApp(service.packageName, label ?: service.packageName, service.name)
            }.distinctBy { it.packageName }
    }

    /**
     * The connection is held across [hold] before disconnecting, because an app may stop a media
     * service that has no clients and nothing playing yet. Disconnecting afterwards does not stop
     * playback: the session outlives the browser connection that revealed it.
     */
    override suspend fun playOn(
        target: MediaApp,
        query: String,
    ): PlayDelivery =
        withContext(Dispatchers.Main.immediate) {
            var browser: MediaBrowser? = null
            try {
                val connected =
                    withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
                        suspendCancellableCoroutine { continuation ->
                            val opening =
                                MediaBrowser(
                                    app,
                                    ComponentName(target.packageName, target.serviceName),
                                    object : MediaBrowser.ConnectionCallback() {
                                        override fun onConnected() {
                                            if (continuation.isActive) continuation.resume(true)
                                        }

                                        override fun onConnectionFailed() {
                                            if (continuation.isActive) continuation.resume(false)
                                        }

                                        override fun onConnectionSuspended() {
                                            if (continuation.isActive) continuation.resume(false)
                                        }
                                    },
                                    null,
                                )
                            browser = opening
                            runCatching { opening.connect() }.onFailure {
                                if (continuation.isActive) continuation.resume(false)
                            }
                        }
                    }
                if (connected != true) return@withContext if (connected == false) PlayDelivery.REFUSED else PlayDelivery.UNREACHABLE
                val token =
                    runCatching { browser?.sessionToken }.getOrNull()
                        ?: return@withContext PlayDelivery.UNREACHABLE
                runCatching { MediaController(app, token).transportControls.playFromSearch(query, Bundle.EMPTY) }
                    .getOrElse { return@withContext PlayDelivery.REFUSED }
                hold()
                PlayDelivery.DELIVERED
            } finally {
                runCatching { browser?.disconnect() }
            }
        }

    companion object {
        const val SERVICE_ACTION = "android.media.browse.MediaBrowserService"

        /** A cold media app has to start a service to answer, but a spoken request cannot wait long. */
        const val CONNECT_TIMEOUT_MILLIS = 4_000L
        const val HOLD_MILLIS = 800L
    }
}
