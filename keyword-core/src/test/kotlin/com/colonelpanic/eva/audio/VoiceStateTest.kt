package com.colonelpanic.eva.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStateTest {
    private fun run(vararg events: VoiceEvent) = events.fold(VoiceFlags()) { flags, event -> flags.on(event) }

    @Test
    fun startsDisconnectedAndListensOnceLinked() {
        assertEquals(VoiceState.DISCONNECTED, VoiceFlags().state)
        assertEquals(VoiceState.LISTENING, run(VoiceEvent.LinkUp).state)
        assertEquals(VoiceState.WORKING, run(VoiceEvent.LinkUp, VoiceEvent.Task(true)).state)
        assertEquals(VoiceState.LISTENING, run(VoiceEvent.LinkUp, VoiceEvent.Task(true), VoiceEvent.Task(false)).state)
    }

    @Test
    fun muteAndSleepSurviveReconnection() {
        assertEquals(VoiceState.MUTED, run(VoiceEvent.LinkUp, VoiceEvent.Mute, VoiceEvent.LinkDown, VoiceEvent.LinkUp).state)
        assertEquals(VoiceState.DISCONNECTED, run(VoiceEvent.Mute, VoiceEvent.Unmute).state)
        assertEquals(VoiceState.ASLEEP, run(VoiceEvent.LinkUp, VoiceEvent.Sleep, VoiceEvent.LinkDown, VoiceEvent.LinkUp).state)
        assertEquals(VoiceState.LISTENING, run(VoiceEvent.LinkUp, VoiceEvent.Sleep, VoiceEvent.Wake).state)
    }

    @Test
    fun priorityIsDisconnectedStoppedAsleepMutedWorking() {
        val all = run(VoiceEvent.Task(true), VoiceEvent.Mute, VoiceEvent.Sleep, VoiceEvent.Stop)
        assertEquals(VoiceState.DISCONNECTED, all.state)
        assertEquals(VoiceState.STOPPED, all.on(VoiceEvent.LinkUp).state)
        assertEquals(VoiceState.ASLEEP, all.on(VoiceEvent.LinkUp).on(VoiceEvent.Resume).state)
        assertEquals(VoiceState.MUTED, run(VoiceEvent.LinkUp, VoiceEvent.Task(true), VoiceEvent.Mute).state)
    }

    @Test
    fun stopEndsWorkAndIgnoresLateWorkReports() {
        val stopped = run(VoiceEvent.LinkUp, VoiceEvent.Task(true), VoiceEvent.Stop, VoiceEvent.Task(true))
        assertEquals(VoiceState.STOPPED, stopped.state)
        assertEquals(VoiceState.LISTENING, stopped.on(VoiceEvent.Resume).state)
    }

    @Test
    fun onlyListeningWorkingAndAnnouncingSendAudio() {
        assertEquals(
            setOf(VoiceState.LISTENING, VoiceState.WORKING, VoiceState.ANNOUNCING),
            VoiceState.entries.filter { it.sendsAudio }.toSet(),
        )
        assertFalse(VoiceState.STOPPED.playsAudio)
    }

    @Test
    fun announcingShowsOverWorkingAndEndsWithAnythingThatTakesAudioAway() {
        val announcing = run(VoiceEvent.LinkUp, VoiceEvent.Task(true), VoiceEvent.Announce(true))
        assertEquals(VoiceState.ANNOUNCING, announcing.state)
        assertEquals(VoiceState.WORKING, announcing.reported)
        assertTrue(VoiceState.ANNOUNCING.sendsAudio && VoiceState.ANNOUNCING.playsAudio)
        assertEquals(VoiceState.WORKING, announcing.on(VoiceEvent.Announce(false)).state)
        for (event in listOf(VoiceEvent.Mute, VoiceEvent.Sleep, VoiceEvent.Stop, VoiceEvent.LinkDown)) {
            assertFalse("$event", announcing.on(event).announcing)
        }
        assertFalse(run(VoiceEvent.LinkUp, VoiceEvent.Mute, VoiceEvent.Announce(true)).announcing)
        assertFalse(run(VoiceEvent.Announce(true)).announcing)
        assertEquals(
            listOf("mute", "sleep", "stop", null, null),
            listOf(VoiceEvent.Mute, VoiceEvent.Sleep, VoiceEvent.Stop, VoiceEvent.LinkDown, VoiceEvent.Announce(false))
                .map(::announcementInterruption),
        )
    }
}
