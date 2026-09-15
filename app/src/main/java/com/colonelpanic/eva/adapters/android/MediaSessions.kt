package com.colonelpanic.eva.adapters.android

/**
 * What EVA knows about one app that is holding a media session. Every app that puts
 * transport controls on the lock screen publishes one, so this is the same picture for
 * Spotify, a podcast player, and a browser tab, without EVA knowing anything about them.
 */
data class MediaSnapshot(
    val packageName: String,
    val appLabel: String,
    val title: String? = null,
    val artist: String? = null,
    val playing: Boolean = false,
    /** Whether the session takes a search request, which is what "play this on Spotify" needs. */
    val canPlayFromSearch: Boolean = false,
    val positionMillis: Long? = null,
    val durationMillis: Long? = null,
    /** What the session says it accepts. Empty when the app declares nothing, which is not a refusal. */
    val supports: Set<MediaCommand> = emptySet(),
)

enum class MediaCommand(
    val argument: String,
) {
    PLAY("play"),
    PAUSE("pause"),
    TOGGLE("toggle"),
    NEXT("next"),
    PREVIOUS("previous"),
    STOP("stop"),
    ;

    /** Only resuming can start an app that is loaded but idle; the rest need something under way. */
    val needsPlayback: Boolean get() = this != PLAY && this != TOGGLE

    /** A session has no toggle, so a toggle becomes whichever of play or pause the state calls for. */
    fun resolve(target: MediaSnapshot): MediaCommand =
        when {
            this != TOGGLE -> this
            target.playing -> PAUSE
            else -> PLAY
        }

    companion object {
        val arguments = entries.map { it.argument }

        fun of(value: String): MediaCommand? = entries.firstOrNull { it.argument == value }
    }
}

enum class VolumeAction(
    val argument: String,
) {
    SET("set"),
    UP("up"),
    DOWN("down"),
    MUTE("mute"),
    UNMUTE("unmute"),
    ;

    companion object {
        val arguments = entries.map { it.argument }

        fun of(value: String): VolumeAction? = entries.firstOrNull { it.argument == value }
    }
}

data class VolumeReport(
    val percent: Int,
    val muted: Boolean,
)

/**
 * How one control request should be carried out. Seeing sessions needs notification access;
 * without it the media button still reaches whatever is playing, so the request is not refused,
 * only reported as something EVA could not watch.
 */
sealed interface MediaPlan {
    data class Control(
        val target: MediaSnapshot,
    ) : MediaPlan

    data object MediaButton : MediaPlan

    data class Refuse(
        val message: String,
    ) : MediaPlan
}

object MediaRouting {
    fun plan(
        observable: Boolean,
        sessions: List<MediaSnapshot>,
        command: MediaCommand,
    ): MediaPlan {
        if (!observable) return MediaPlan.MediaButton
        val target =
            sessions.firstOrNull { it.playing }
                ?: sessions.firstOrNull()
                ?: return if (command.needsPlayback) MediaPlan.Refuse(NOTHING_PLAYING) else MediaPlan.MediaButton
        return refusalFor(target, command) ?: MediaPlan.Control(target)
    }

    /** An app that declares no actions at all is attempted anyway; only an explicit omission refuses. */
    internal fun refusalFor(
        target: MediaSnapshot,
        command: MediaCommand,
    ): MediaPlan.Refuse? {
        if (target.supports.isEmpty() || command.resolve(target) in target.supports) return null
        return MediaPlan.Refuse("${target.appLabel} does not accept ${command.argument} right now. Nothing was sent to it.")
    }

    const val NOTHING_PLAYING = "No app on this phone is playing or paused on anything. Nothing was sent."
}

/** An installed app that publishes a media browser service, which is what can be asked to play. */
data class MediaApp(
    val packageName: String,
    val label: String,
    val serviceName: String,
)
