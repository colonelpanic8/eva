package com.colonelpanic.eva.adapters.android

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.view.KeyEvent
import kotlin.math.roundToInt

/**
 * The phone's media sessions, as EVA is allowed to see and drive them. Nothing here needs EVA
 * to be on screen, which is what separates it from the intent capabilities: a spoken "pause"
 * works from a locked phone, where starting an activity does not.
 */
interface MediaSessionAccess {
    /** Whether the notification-listener grant that reveals other apps' sessions is on. */
    fun observable(): Boolean

    /** Active sessions, the one that would receive a media button first. Empty without the grant. */
    fun sessions(): List<MediaSnapshot>

    /** False when the named session is gone, which is the one case a command is not delivered. */
    fun send(
        packageName: String,
        command: MediaCommand,
    ): Boolean

    /**
     * Asks a live session to play whatever the words match, the way a car's voice button does.
     * This reaches an app that refuses EVA as a media browser client, because the session came
     * from the notification-listener grant rather than from the app's own allow list.
     */
    fun playFromSearch(
        packageName: String,
        query: String,
    ): Boolean

    /** The unprivileged path: a media button reaches whatever is playing, unseen and unconfirmed. */
    fun sendMediaButton(command: MediaCommand): Boolean

    fun volume(): VolumeReport?

    /** Null when Android refused the change, which Do Not Disturb does without a policy grant. */
    fun changeVolume(
        action: VolumeAction,
        percent: Int?,
    ): VolumeReport?
}

class AndroidMediaSessions(
    context: Context,
) : MediaSessionAccess {
    private val app = context.applicationContext

    private val audio: AudioManager? get() = app.getSystemService(AudioManager::class.java)

    override fun observable(): Boolean = MediaControlAccess.isGranted(app)

    override fun sessions(): List<MediaSnapshot> = controllers().map(::snapshot)

    override fun send(
        packageName: String,
        command: MediaCommand,
    ): Boolean {
        val controls = controllers().firstOrNull { it.packageName == packageName }?.transportControls ?: return false
        when (command) {
            MediaCommand.PLAY -> controls.play()

            MediaCommand.PAUSE -> controls.pause()

            MediaCommand.NEXT -> controls.skipToNext()

            MediaCommand.PREVIOUS -> controls.skipToPrevious()

            MediaCommand.STOP -> controls.stop()

            // A session has no toggle; the caller resolves one against the session's own state first.
            MediaCommand.TOGGLE -> controls.play()
        }
        return true
    }

    override fun playFromSearch(
        packageName: String,
        query: String,
    ): Boolean {
        val controls = controllers().firstOrNull { it.packageName == packageName }?.transportControls ?: return false
        controls.playFromSearch(query, Bundle.EMPTY)
        return true
    }

    override fun sendMediaButton(command: MediaCommand): Boolean {
        val manager = audio ?: return false
        val code = keyCode(command)
        manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return true
    }

    override fun volume(): VolumeReport? {
        val manager = audio ?: return null
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: return null
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return VolumeReport(
            percent = (current * 100f / max).roundToInt().coerceIn(0, 100),
            muted = manager.isStreamMute(AudioManager.STREAM_MUSIC) || current == 0,
        )
    }

    override fun changeVolume(
        action: VolumeAction,
        percent: Int?,
    ): VolumeReport? {
        val manager = audio ?: return null
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: return null
        try {
            when (action) {
                VolumeAction.SET -> {
                    val index = (max * (percent ?: return null) / 100f).roundToInt().coerceIn(0, max)
                    manager.setStreamVolume(AudioManager.STREAM_MUSIC, index, AudioManager.FLAG_SHOW_UI)
                }

                VolumeAction.UP -> {
                    adjust(manager, AudioManager.ADJUST_RAISE)
                }

                VolumeAction.DOWN -> {
                    adjust(manager, AudioManager.ADJUST_LOWER)
                }

                VolumeAction.MUTE -> {
                    adjust(manager, AudioManager.ADJUST_MUTE)
                }

                VolumeAction.UNMUTE -> {
                    adjust(manager, AudioManager.ADJUST_UNMUTE)
                }
            }
        } catch (_: SecurityException) {
            return null
        }
        return volume()
    }

    private fun adjust(
        manager: AudioManager,
        direction: Int,
    ) = manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)

    /**
     * Empty rather than an error when the grant is absent: the caller falls back to the media
     * button, which needs no grant, instead of refusing a request it can still deliver.
     */
    private fun controllers(): List<MediaController> =
        runCatching {
            app
                .getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(MediaControlAccess.component(app))
                .orEmpty()
        }.getOrDefault(emptyList())

    private fun snapshot(controller: MediaController): MediaSnapshot {
        val metadata = controller.metadata
        val state = controller.playbackState
        return MediaSnapshot(
            packageName = controller.packageName,
            appLabel = label(controller.packageName),
            title = metadata?.text(MediaMetadata.METADATA_KEY_TITLE),
            artist =
                metadata?.text(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: metadata?.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            playing = state?.state == PlaybackState.STATE_PLAYING,
            canPlayFromSearch = (state?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L,
            positionMillis = state?.position?.takeIf { it >= 0 },
            durationMillis = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
            supports = supported(state?.actions ?: 0L),
        )
    }

    private fun MediaMetadata.text(key: String): String? = getString(key)?.trim()?.takeIf(String::isNotBlank)

    private fun supported(actions: Long): Set<MediaCommand> =
        buildSet {
            if (actions and PlaybackState.ACTION_PLAY != 0L) add(MediaCommand.PLAY)
            if (actions and PlaybackState.ACTION_PAUSE != 0L) add(MediaCommand.PAUSE)
            if (actions and PlaybackState.ACTION_PLAY_PAUSE != 0L) {
                add(MediaCommand.PLAY)
                add(MediaCommand.PAUSE)
            }
            if (actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) add(MediaCommand.NEXT)
            if (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) add(MediaCommand.PREVIOUS)
            if (actions and PlaybackState.ACTION_STOP != 0L) add(MediaCommand.STOP)
        }

    /** The name the user would say. The launcher query in the manifest is what makes it readable. */
    private fun label(packageName: String): String =
        runCatching {
            val packages = app.packageManager
            packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString().trim()
        }.getOrNull()?.takeIf(String::isNotBlank) ?: packageName

    private fun keyCode(command: MediaCommand): Int =
        when (command) {
            MediaCommand.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaCommand.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaCommand.TOGGLE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaCommand.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaCommand.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            MediaCommand.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
        }
}
