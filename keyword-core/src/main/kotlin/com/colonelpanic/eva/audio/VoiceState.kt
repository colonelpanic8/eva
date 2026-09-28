package com.colonelpanic.eva.audio

import com.colonelpanic.eva.keyword.VoiceNotice

enum class VoiceState {
    ASLEEP,
    LISTENING,
    MUTED,
    WORKING,
    STOPPED,
    DISCONNECTED,

    /** A local announcement is playing; listening or working underneath. */
    ANNOUNCING,
    ;

    /** The only states in which audio may leave the device; local detection can keep recording. */
    val sendsAudio: Boolean get() = this == LISTENING || this == WORKING || this == ANNOUNCING

    /** States that keep host audio; the others flush it and drop what still arrives. */
    val playsAudio: Boolean get() = sendsAudio || this == MUTED

    val wireName: String get() = name.lowercase()
}

sealed interface VoiceEvent {
    data object LinkUp : VoiceEvent

    data object LinkDown : VoiceEvent

    data object Mute : VoiceEvent

    data object Unmute : VoiceEvent

    data object Sleep : VoiceEvent

    data object Wake : VoiceEvent

    data object Stop : VoiceEvent

    data object Resume : VoiceEvent

    data class Task(
        val working: Boolean,
    ) : VoiceEvent

    data class Announce(
        val active: Boolean,
    ) : VoiceEvent
}

/**
 * The service's independent conditions; the displayed [state] is derived from them by priority,
 * so, for example, unmuting while disconnected still leaves the device disconnected, and
 * reconnecting restores whatever mute or sleep the user had chosen. Anything that takes audio
 * away also ends an announcement.
 */
data class VoiceFlags(
    val connected: Boolean = false,
    val asleep: Boolean = false,
    val muted: Boolean = false,
    val stopped: Boolean = false,
    val working: Boolean = false,
    val announcing: Boolean = false,
) {
    val state: VoiceState
        get() =
            when {
                !connected -> VoiceState.DISCONNECTED
                stopped -> VoiceState.STOPPED
                asleep -> VoiceState.ASLEEP
                muted -> VoiceState.MUTED
                announcing -> VoiceState.ANNOUNCING
                working -> VoiceState.WORKING
                else -> VoiceState.LISTENING
            }

    /** The state reported to the host, which knows announcements already and adopts only the others. */
    val reported: VoiceState get() = copy(announcing = false).state

    fun on(event: VoiceEvent): VoiceFlags =
        when (event) {
            VoiceEvent.LinkUp -> copy(connected = true)
            VoiceEvent.LinkDown -> copy(connected = false, working = false, announcing = false)
            VoiceEvent.Mute -> copy(muted = true, announcing = false)
            VoiceEvent.Unmute -> copy(muted = false)
            VoiceEvent.Sleep -> copy(asleep = true, announcing = false)
            VoiceEvent.Wake -> copy(asleep = false)
            VoiceEvent.Stop -> copy(stopped = true, working = false, announcing = false)
            VoiceEvent.Resume -> copy(stopped = false)
            is VoiceEvent.Task -> copy(working = event.working && !stopped)
            is VoiceEvent.Announce -> copy(announcing = event.active && state.sendsAudio)
        }
}

/** The notice sent to the host when a local input changes the state. */
fun notice(event: VoiceEvent): VoiceNotice? =
    when (event) {
        VoiceEvent.Mute -> VoiceNotice.Mute
        VoiceEvent.Unmute -> VoiceNotice.Unmute
        VoiceEvent.Sleep -> VoiceNotice.Sleep
        VoiceEvent.Wake -> VoiceNotice.Wake
        VoiceEvent.Stop -> VoiceNotice.Stop
        VoiceEvent.Resume -> VoiceNotice.Resume
        else -> null
    }

/** Why an event that ended the announcing state interrupts the announcement, as sent to the host. */
fun announcementInterruption(event: VoiceEvent): String? =
    when (event) {
        VoiceEvent.Mute -> "mute"
        VoiceEvent.Sleep -> "sleep"
        VoiceEvent.Stop -> "stop"
        else -> null
    }
