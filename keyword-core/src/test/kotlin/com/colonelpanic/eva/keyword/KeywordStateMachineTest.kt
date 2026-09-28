@file:OptIn(ExperimentalCoroutinesApi::class)

package com.colonelpanic.eva.keyword

import com.colonelpanic.eva.audio.VoiceEvent
import com.colonelpanic.eva.audio.VoiceState
import com.colonelpanic.eva.keyword.VoiceNotice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

class KeywordStateMachineTest {
    private class FakeAudioGate : AudioTransmissionGate {
        var allowed = false
        val binary = mutableListOf<ByteArray>()

        override fun setAudioAllowed(allowed: Boolean) {
            this.allowed = allowed
        }

        fun sendAudio(pcm: ByteArray) {
            if (allowed) binary += pcm
        }
    }

    private class FakeLatch {
        data class Status(
            val stopped: Boolean = false,
            val stopSource: StopSource? = null,
        )

        val status = kotlinx.coroutines.flow.MutableStateFlow(Status())

        fun stop(source: StopSource) {
            status.value = Status(true, source)
        }

        fun resume() {
            status.value = Status()
        }
    }

    private val session =
        object : VoiceSessionControl {
            override fun wake() {
                log += "session:wake"
            }

            override fun sleep() {
                log += "session:sleep"
            }
        }

    /** Everything the machine does to the outside, in order. */
    private val log = CopyOnWriteArrayList<String>()
    private val notices = CopyOnWriteArrayList<VoiceNotice>()
    private val gate = FakeLatch()
    private var now = 0L
    private val pcm = ByteArray(960)

    private val roles = PhraseRoles(wake = "hey_jarvis", stop = "stop_model", mute = "mute_model", unmute = "unmute_model")

    private inner class LoggingLatch : StopLatch {
        override fun stop(source: StopSource) {
            log += "latch:$source"
            gate.stop(source)
        }

        override fun resume() {
            log += "resume"
            gate.resume()
        }
    }

    private inner class LinkUplink(
        private val link: FakeAudioGate,
    ) : AudioTransmissionGate,
        KeywordNotices {
        override fun setAudioAllowed(allowed: Boolean) {
            log += "gate:$allowed"
            link.setAudioAllowed(allowed)
        }

        override fun notify(message: VoiceNotice) {
            log += "notify:${message.name.lowercase()}"
            notices += message
        }
    }

    private fun machine(
        link: FakeAudioGate,
        idleTimeoutMs: Long? = 60_000,
        startAsleep: Boolean = true,
    ) = KeywordStateMachine(roles, LoggingLatch(), LinkUplink(link), session, LinkUplink(link), { now }, idleTimeoutMs, startAsleep).also {
        it.handle(VoiceEvent.LinkUp, Origin.LINK)
    }

    private fun keyword(
        phrase: String,
        onsetMs: Long? = null,
    ) = KeywordEvent(phrase, 0.9f, now, onsetMs?.let { now - it * 1_000_000 })

    /** What the service's capture pump does with every captured frame: offer it to the link. */
    private fun pump(
        link: FakeAudioGate,
        frames: Int = 10,
    ) = repeat(frames) { link.sendAudio(pcm) }

    /** What the service does after a transition, once the recorder runs. */
    private fun KeywordStateMachine.afterTransition() {
        openAudioIfAllowed()
    }

    @Test
    fun asleepAndMutedLetNoFrameOutThroughTheLinkGate() =
        runTest {
            val link = FakeAudioGate()
            val socket = link
            val machine = machine(link)
            machine.afterTransition()
            assertEquals(KeywordState.ASLEEP, machine.state)
            pump(link, 50)
            assertEquals(0, socket.binary.size)

            machine.onKeyword(keyword("hey_jarvis"))
            machine.afterTransition()
            assertEquals(KeywordState.LISTENING, machine.state)
            pump(link)
            assertEquals(10, socket.binary.size)

            log.clear()
            machine.onKeyword(keyword("mute_model"))
            assertEquals(listOf("gate:false", "notify:mute"), log)
            machine.afterTransition()
            pump(link, 50)
            assertEquals(KeywordState.MUTED, machine.state)
            assertEquals(10, socket.binary.size)

            machine.onKeyword(keyword("hey_jarvis"))
            assertEquals("wake does not unmute", KeywordState.MUTED, machine.state)
            machine.onKeyword(keyword("unmute_model"))
            machine.afterTransition()
            pump(link)
            assertEquals(20, socket.binary.size)

            now += 61_000_000_000
            assertTrue(machine.tick())
            machine.afterTransition()
            pump(link, 50)
            assertEquals(KeywordState.ASLEEP, machine.state)
            assertEquals(20, socket.binary.size)
            assertEquals(VoiceNotice.Sleep, notices.last())
        }

    @Test
    fun randomEventSequencesNeverLeakAudioOrLetTheHostReopenIt() =
        runTest {
            val link = FakeAudioGate()
            val socket = link
            val machine = machine(link)
            val random = Random(7)
            val phrases = listOf("hey_jarvis", "stop_model", "mute_model", "unmute_model", "other")
            val events =
                listOf(
                    VoiceEvent.Mute,
                    VoiceEvent.Unmute,
                    VoiceEvent.Sleep,
                    VoiceEvent.Wake,
                    VoiceEvent.Stop,
                    VoiceEvent.Resume,
                    VoiceEvent.Task(true),
                    VoiceEvent.Task(false),
                )
            repeat(5_000) {
                val before = machine.flags
                when (random.nextInt(8)) {
                    0, 1 -> {
                        machine.onKeyword(keyword(phrases.random(random)))
                    }

                    2 -> {
                        machine.handle(events.random(random), Origin.LOCAL)
                    }

                    3 -> {
                        machine.handle(events.random(random), Origin.HOST)
                        val after = machine.flags
                        assertFalse(before.asleep && !after.asleep)
                        assertFalse(before.muted && !after.muted)
                        assertFalse(before.stopped && !after.stopped)
                    }

                    4 -> {
                        machine.handle(if (random.nextBoolean()) VoiceEvent.LinkUp else VoiceEvent.LinkDown, Origin.LINK)
                    }

                    5 -> {
                        now += random.nextLong(0, 90_000_000_000)
                        machine.tick()
                    }

                    6 -> {
                        if (random.nextBoolean()) {
                            val source = StopSource.entries.random(random)
                            gate.stop(source)
                            machine.onGate(true, source)
                        } else {
                            gate.resume()
                            machine.onGate(false, null)
                        }
                        assertEquals(gate.status.value.stopped, machine.flags.stopped)
                    }

                    else -> {
                        machine.afterTransition()
                    }
                }
                val sent = socket.binary.size
                pump(link, 3)
                if (!machine.sendsAudio) assertEquals("frames left in ${machine.flags}", sent, socket.binary.size)
            }
            assertTrue("the sequence exercised sending", socket.binary.size > 0)
            val allowed =
                setOf(VoiceNotice.Mute, VoiceNotice.Unmute, VoiceNotice.Sleep, VoiceNotice.Wake, VoiceNotice.Stop, VoiceNotice.Resume)
            assertTrue(notices.all { it in allowed })
        }

    @Test
    fun stopLatchesLocallyFirstFromEveryStateWithTheHostUnreachable() =
        runTest {
            val link = FakeAudioGate()
            for (setup in listOf<(KeywordStateMachine) -> Unit>(
                {},
                { it.handle(VoiceEvent.Stop, Origin.LOCAL) },
                { it.handle(VoiceEvent.LinkDown, Origin.LINK) },
                {
                    it.onKeyword(keyword("hey_jarvis"))
                    it.handle(VoiceEvent.Task(true), Origin.LOCAL)
                },
                {
                    it.onKeyword(keyword("hey_jarvis"))
                    it.announce(true)
                },
                { it.onKeyword(keyword("hey_jarvis")) },
                {
                    it.onKeyword(keyword("hey_jarvis"))
                    it.onKeyword(keyword("mute_model"))
                },
            )) {
                gate.resume()
                val machine = machine(link)
                setup(machine)
                val state = machine.state
                log.clear()
                now += 5_000_000
                machine.onKeyword(keyword("stop_model", onsetMs = 450))
                assertEquals("from $state", "latch:KEYWORD", log.first())
                assertTrue(gate.status.value.stopped)
                assertEquals(StopSource.KEYWORD, gate.status.value.stopSource)
                assertEquals(KeywordState.STOPPED, machine.state)
                assertEquals(VoiceNotice.Stop, notices.last())
                val timing = machine.lastStop
                assertNotNull(timing)
                assertEquals(0L, timing!!.detectionToLatchNanos)
                assertEquals(450_000_000L, timing.detectedAtNanos - timing.speechOnsetNanos!!)
            }
        }

    @Test
    fun mutingKeepsTheTaskRunningAndLeavesStopAndUnmuteArmed() =
        runTest {
            val link = FakeAudioGate()
            val machine = machine(link, startAsleep = false)
            machine.handle(VoiceEvent.Task(true), Origin.HOST)
            machine.onKeyword(keyword("mute_model"))
            assertEquals(KeywordState.MUTED, machine.state)
            assertTrue("a running task keeps running", machine.flags.working)
            assertFalse(gate.status.value.stopped)
            assertTrue(log.none { it.startsWith("latch") })

            now += 3_600_000_000_000
            assertFalse("muted never times out", machine.tick())
            machine.onKeyword(keyword("unmute_model"))
            assertEquals(KeywordState.LISTENING, machine.state)
            machine.onKeyword(keyword("mute_model"))
            machine.onKeyword(keyword("stop_model"))
            assertEquals(KeywordState.STOPPED, machine.state)
            assertTrue(gate.status.value.stopped)
            assertFalse(machine.flags.working)
        }

    @Test
    fun anAnnouncementShowsOverListeningOrWorkingAndMuteOrStopEndsIt() =
        runTest {
            val link = FakeAudioGate()
            val transitions = CopyOnWriteArrayList<Transition>()
            val machine =
                KeywordStateMachine(
                    roles,
                    LoggingLatch(),
                    LinkUplink(link),
                    session,
                    LinkUplink(link),
                    { now },
                    startAsleep = false,
                    onTransition = { transition ->
                        log += "transition:${transition.event}:stopped=${gate.status.value.stopped}"
                        transitions += transition
                    },
                ).also { it.handle(VoiceEvent.LinkUp, Origin.LINK) }
            assertTrue(machine.announce(true))
            assertEquals(VoiceState.ANNOUNCING, machine.flags.state)
            assertEquals(VoiceState.LISTENING, machine.flags.reported)
            assertEquals(KeywordState.LISTENING, machine.state)
            assertTrue(machine.sendsAudio)
            now += 3_600_000_000_000
            assertFalse("an announcement is not idle", machine.tick())
            assertTrue(machine.announce(false))
            assertEquals(VoiceState.LISTENING, machine.flags.state)

            machine.handle(VoiceEvent.Task(true), Origin.HOST)
            machine.announce(true)
            assertEquals(VoiceState.WORKING, machine.flags.reported)
            machine.onKeyword(keyword("mute_model"))
            assertEquals(VoiceState.MUTED, machine.flags.state)
            assertTrue(transitions.last().before.announcing)
            assertFalse(transitions.last().after.announcing)
            assertFalse("muted refuses an announcement", machine.announce(true))
            assertFalse(machine.flags.announcing)

            machine.onKeyword(keyword("unmute_model"))
            assertTrue(machine.announce(true))
            log.clear()
            transitions.clear()
            machine.onKeyword(keyword("stop_model"))
            assertEquals(listOf("latch:KEYWORD", "gate:false"), log.take(2))
            assertTrue(log.contains("transition:${VoiceEvent.Stop}:stopped=true"))
            val stop = transitions.single()
            assertTrue(stop.before.announcing)
            assertFalse(stop.after.announcing)
            assertEquals(VoiceState.STOPPED, machine.flags.state)
            assertFalse(machine.announce(true))
        }

    @Test
    fun wakeResumesAfterStopButTheHostCannot() =
        runTest {
            val link = FakeAudioGate()
            val machine = machine(link, startAsleep = false)
            machine.onKeyword(keyword("stop_model"))
            machine.handle(VoiceEvent.Resume, Origin.HOST)
            machine.handle(VoiceEvent.Wake, Origin.HOST)
            assertEquals(KeywordState.STOPPED, machine.state)
            assertTrue(gate.status.value.stopped)

            now += 61_000_000_000
            assertTrue("stopped also sleeps when idle", machine.tick())
            assertTrue(machine.flags.asleep)
            machine.onKeyword(keyword("hey_jarvis"))
            assertEquals(KeywordState.LISTENING, machine.state)
            assertFalse(gate.status.value.stopped)
            assertEquals(listOf(VoiceNotice.Resume, VoiceNotice.Wake), notices.takeLast(2))
        }

    @Test
    fun idleTimeoutCountsFromTheLastSpeechAndWaitsForTasks() =
        runTest {
            val link = FakeAudioGate()
            val machine = machine(link)
            machine.onKeyword(keyword("hey_jarvis"))
            now += 40_000_000_000
            machine.onActivity(now)
            now += 59_000_000_000
            assertFalse(machine.tick())
            machine.handle(VoiceEvent.Task(true), Origin.HOST)
            now += 120_000_000_000
            assertFalse("a working task keeps the session open", machine.tick())
            machine.handle(VoiceEvent.Task(false), Origin.HOST)
            now += 59_000_000_000
            assertFalse(machine.tick())
            now += 1_000_000_000
            assertTrue(machine.tick())
            assertEquals(KeywordState.ASLEEP, machine.state)
        }

    @Test
    fun aStopFromElsewhereIsMirroredIntoTheVoiceState() =
        runTest {
            val link = FakeAudioGate()
            val machine = machine(link, startAsleep = false)
            machine.afterTransition()
            gate.stop(StopSource.UI)
            log.clear()
            machine.onGate(true, StopSource.UI)
            assertEquals(KeywordState.STOPPED, machine.state)
            assertEquals(listOf("gate:false", "notify:stop"), log)
            machine.onGate(true, StopSource.UI)
            assertEquals("idempotent", 2, log.size)

            gate.resume()
            machine.onGate(false, null)
            assertEquals(KeywordState.LISTENING, machine.state)
            assertEquals("a device stop cleared on the device is announced", VoiceNotice.Resume, notices.last())
            assertTrue("the machine does not clear the latch again", log.none { it == "resume" })
        }

    @Test
    fun aHostOnlyStopFollowsTheGateSilentlyBothWays() =
        runTest {
            val link = FakeAudioGate()
            val socket = link
            val machine = machine(link, startAsleep = false)
            machine.afterTransition()
            machine.handle(VoiceEvent.Stop, Origin.HOST)
            assertEquals(listOf("latch:HOST", "gate:false"), log.takeLast(2))
            assertEquals(KeywordState.STOPPED, machine.state)
            pump(link)
            assertEquals(0, socket.binary.size)
            machine.onGate(true, StopSource.HOST)

            log.clear()
            gate.resume()
            machine.onGate(false, null)
            assertEquals("the host re-handshake cleared its own stop", KeywordState.LISTENING, machine.state)
            assertEquals(emptyList<VoiceNotice>(), notices)
            assertTrue(log.none { it == "resume" || it.startsWith("notify") })
        }

    @Test
    fun aKeywordStopOnTopOfAHostStopIsAnnouncedWhenCleared() =
        runTest {
            val link = FakeAudioGate()
            val machine = machine(link, startAsleep = false)
            machine.handle(VoiceEvent.Stop, Origin.HOST)
            machine.onKeyword(keyword("stop_model"))
            assertEquals(StopSource.KEYWORD, gate.status.value.stopSource)
            gate.resume()
            machine.onGate(false, null)
            assertEquals(VoiceNotice.Resume, notices.last())
        }

    @Test
    fun rolesRejectAStopPhraseSharedWithAnotherRole() {
        val shared = runCatching { PhraseRoles(wake = "a", stop = "a") }
        assertTrue(shared.isFailure)
        assertEquals(setOf("a", "b"), PhraseRoles(wake = "a", mute = "b", unmute = "b").all)
    }
}
