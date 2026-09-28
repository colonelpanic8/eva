package com.voicedeviceagent.companion.keyword

import com.voicedeviceagent.companion.audio.VoiceEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordPortsTest {
    @Test
    fun gateClosesBeforeMutedStateIsPublishedAndMuteNeverControlsSessionOrLatch() {
        val effects = mutableListOf<String>()
        lateinit var machine: KeywordStateMachine
        val gate =
            object : AudioTransmissionGate {
                override fun setAudioAllowed(allowed: Boolean) {
                    effects += "gate:$allowed:muted=${machine.flags.muted}"
                }
            }
        val latch =
            object : StopLatch {
                override fun stop(source: StopSource) {
                    effects += "stop"
                }

                override fun resume() {
                    effects += "resume"
                }
            }
        val session =
            object : VoiceSessionControl {
                override fun wake() {
                    effects += "wake"
                }

                override fun sleep() {
                    effects += "sleep"
                }
            }
        machine =
            KeywordStateMachine(
                PhraseRoles("wake", "stop", "mute", "unmute"),
                latch,
                gate,
                session,
                KeywordNotices { effects += "notice:$it" },
                { 0L },
            )
        machine.handle(VoiceEvent.LinkUp, Origin.LINK)
        machine.onKeyword(KeywordEvent("wake", 1f, 0, null))
        assertTrue(effects.contains("wake"))
        machine.openAudioIfAllowed()
        effects.clear()
        machine.onKeyword(KeywordEvent("mute", 1f, 0, null))
        assertEquals(listOf("gate:false:muted=false", "notice:Mute"), effects)
        assertFalse(machine.openAudioIfAllowed())
        machine.onKeyword(KeywordEvent("unmute", 1f, 0, null))
        assertTrue(machine.openAudioIfAllowed())
        effects.clear()
        machine.handle(VoiceEvent.Sleep, Origin.LOCAL)
        machine.handle(VoiceEvent.Sleep, Origin.LOCAL)
        assertEquals(1, effects.count { it == "sleep" })
    }

    @Test
    fun nonFiniteScoresCannotTriggerOrCountTowardPatience() {
        val detector = PhraseDetector(PhraseSpec("wake", patience = 2))
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFalse(detector.offer(0.9f, 0))
            assertFalse(detector.offer(bad, 1))
            assertFalse(detector.offer(0.9f, 2))
            detector.offer(0f, 3)
        }
    }
}
