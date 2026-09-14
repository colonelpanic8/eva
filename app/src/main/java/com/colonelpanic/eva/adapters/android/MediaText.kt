package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus

/**
 * What EVA says about media it can only observe. A transport command is delivered to another
 * process and answered asynchronously, so every outcome here distinguishes what the session
 * afterwards actually showed from what the command was supposed to do.
 */
object MediaText {
    fun describe(
        sessions: List<MediaSnapshot>,
        volume: VolumeReport?,
    ): String {
        val playing =
            if (sessions.isEmpty()) {
                "No app on this phone is playing or paused on anything."
            } else {
                sessions.joinToString("\n", transform = ::one)
            }
        return listOfNotNull(playing, volume?.let(::volume)).joinToString("\n")
    }

    fun one(snapshot: MediaSnapshot): String {
        val track = track(snapshot)
        if (track == null) {
            return "${snapshot.appLabel} ${if (snapshot.playing) "is playing" else "is paused"}, with no track name."
        }
        val verb = if (snapshot.playing) "is playing" else "is paused on"
        return "${snapshot.appLabel} $verb $track${progress(snapshot)}."
    }

    fun appList(sessions: List<MediaSnapshot>): String = "Media controls are published by ${sessions.joinToString(", ") { it.appLabel }}."

    fun volume(report: VolumeReport): String = if (report.muted) "Media volume is muted." else "Media volume is ${report.percent}%."

    fun buttonSent(
        command: MediaCommand,
        observable: Boolean,
    ): String {
        val button = button(command)
        return if (observable) {
            "No app was publishing media controls, so EVA sent the $button button in case one is loaded but idle. " +
                "It cannot confirm that anything started."
        } else {
            "EVA sent the $button button to whatever holds this phone's media controls. Without notification access " +
                "it cannot see which app received it or what is playing now; turn on media controls in EVA's settings " +
                "to get an answer instead of a button press."
        }
    }

    /**
     * The session as it read after the command, never the command restated. A session that is gone
     * is only evidence for having stopped; for anything else it is the point at which EVA stops knowing.
     */
    fun confirm(
        command: MediaCommand,
        before: MediaSnapshot,
        after: MediaSnapshot?,
    ): ExecutionOutcome {
        val label = before.appLabel
        if (after == null) {
            return when (command) {
                MediaCommand.STOP, MediaCommand.PAUSE -> {
                    completed("$label released its media controls, so it is no longer playing.")
                }

                else -> {
                    unknown("$label released its media controls before EVA could read the result.")
                }
            }
        }
        return when (command) {
            MediaCommand.STOP -> {
                if (after.playing) unknown("$label is still playing ${track(after) ?: "something"}.") else completed("Stopped $label.")
            }

            MediaCommand.PAUSE -> {
                if (after.playing) unknown("$label is still playing ${track(after) ?: "something"}.") else completed(one(after))
            }

            MediaCommand.PLAY -> {
                if (after.playing) completed(one(after)) else unknown("$label did not start playing. It still shows ${state(after)}.")
            }

            MediaCommand.NEXT, MediaCommand.PREVIOUS -> {
                if (after.title != before.title) {
                    completed("$label moved to ${track(after) ?: "an unnamed track"}.")
                } else {
                    unknown("$label still shows ${state(after)}, so the track may not have changed.")
                }
            }

            MediaCommand.TOGGLE -> {
                unknown("$label answered a toggle EVA could not resolve into play or pause.")
            }
        }
    }

    fun askedToPlay(
        label: String,
        query: String,
    ): String =
        "Asked $label to play \"$query\". $label decides what those words match, and without notification " +
            "access EVA cannot see what started."

    /** What the app actually began playing is the answer; the request is only what was asked for. */
    fun confirmStarted(
        label: String,
        query: String,
        after: MediaSnapshot?,
    ): ExecutionOutcome =
        when {
            after == null -> unknown("$label took the request for \"$query\" but is not publishing any playback.")
            after.playing -> completed("${one(after)} That is $label's match for \"$query\".")
            else -> unknown("$label took the request for \"$query\" but is not playing; it shows ${state(after)}.")
        }

    private fun completed(message: String) = ExecutionOutcome(InvocationStatus.COMPLETED, message)

    private fun unknown(message: String) = ExecutionOutcome(InvocationStatus.UNKNOWN, message)

    private fun state(snapshot: MediaSnapshot) = track(snapshot) ?: "no track name"

    private fun track(snapshot: MediaSnapshot): String? {
        val title = snapshot.title?.trim()?.takeIf(String::isNotBlank) ?: return null
        val artist = snapshot.artist?.trim()?.takeIf(String::isNotBlank)
        return if (artist == null) "\"$title\"" else "\"$title\" by $artist"
    }

    private fun progress(snapshot: MediaSnapshot): String {
        val duration = snapshot.durationMillis?.takeIf { it > 0 } ?: return ""
        val position = snapshot.positionMillis?.takeIf { it >= 0 } ?: return ", ${clock(duration)} long"
        return ", ${clock(position)} of ${clock(duration)}"
    }

    private fun clock(millis: Long): String {
        val total = millis / 1000
        val minutes = total / 60 % 60
        val seconds = total % 60
        val hours = total / 3600
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    private fun button(command: MediaCommand): String =
        when (command) {
            MediaCommand.TOGGLE -> "play/pause"
            MediaCommand.NEXT -> "next track"
            MediaCommand.PREVIOUS -> "previous track"
            else -> command.argument
        }
}
