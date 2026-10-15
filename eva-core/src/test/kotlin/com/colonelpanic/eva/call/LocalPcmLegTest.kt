package com.colonelpanic.eva.call

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalPcmLegTest {
    private fun tracker() = LegStateTracker(placing = true) { 42 }

    @Test fun ringsThenConnectsOnceWithTheFirstAnswerEvidence() {
        val leg = tracker()
        leg.observe(LegObservation.Pending)
        assertEquals(LegState.Placing, leg.state.value)
        leg.observe(LegObservation.Live(null))
        assertEquals(LegState.Ringing, leg.state.value)
        leg.observe(LegObservation.Live(AnsweredEvidence.PRECISE_CALL_STATE))
        leg.observe(LegObservation.Live(AnsweredEvidence.PROVIDER_ASSERTION))
        assertEquals(LegState.Connected(42, AnsweredEvidence.PRECISE_CALL_STATE), leg.state.value)
    }

    @Test fun endCauseWeighsHangUpRequestAndWhetherTheCallWasAnswered() {
        fun ended(
            answered: Boolean,
            cause: EndCause?,
            hungUp: Boolean = false,
        ): EndCause {
            val leg = tracker()
            leg.hangUpRequested = hungUp
            if (answered) leg.observe(LegObservation.Live(AnsweredEvidence.PROVIDER_ASSERTION))
            leg.observe(LegObservation.Gone(cause, "detail"))
            return (leg.state.value as LegState.Ended).cause
        }
        assertEquals(EndCause.LOCAL_HANGUP, ended(answered = true, cause = EndCause.REMOTE_HANGUP, hungUp = true))
        assertEquals(EndCause.BUSY, ended(answered = false, cause = EndCause.BUSY))
        assertEquals(EndCause.NO_ANSWER, ended(answered = false, cause = EndCause.REMOTE_HANGUP))
        assertEquals(EndCause.FAILED, ended(answered = false, cause = EndCause.FAILED))
        assertEquals(EndCause.REMOTE_HANGUP, ended(answered = true, cause = EndCause.REMOTE_HANGUP))
        assertEquals(EndCause.DROPPED, ended(answered = true, cause = null))
    }

    @Test fun losingObservationNeverConfirmsTheCallEndedEvenAfterHangUpWasRequested() {
        val lost = tracker()
        lost.lost("the helper", "Check the phone.")
        assertEquals(LegState.Ended(EndCause.FAILED, "Lost the helper. Check the phone.", endConfirmed = false), lost.state.value)
        lost.observe(LegObservation.Live(AnsweredEvidence.PROVIDER_ASSERTION))
        assertTrue(lost.state.value is LegState.Ended)

        val hungUp = tracker()
        hungUp.hangUpRequested = true
        hungUp.lost("the helper", "Check the phone.")
        assertEquals(EndCause.FAILED, (hungUp.state.value as LegState.Ended).cause)
        assertEquals(false, (hungUp.state.value as LegState.Ended).endConfirmed)
    }

    @Test fun uplinkResamplesQueuesInOrderAndPadsWithSilence() {
        val uplink = PacedUplink(16_000)
        uplink.enqueue(PcmFrame(ShortArray(80) { 100 }, 8_000))
        uplink.enqueue(PcmFrame(ShortArray(100) { 7 }, 16_000))
        val frame = uplink.nextFrame()
        assertEquals(320, frame.size)
        assertTrue(frame.take(160).all { it == 100.toShort() })
        assertTrue(frame.slice(160 until 260).all { it == 7.toShort() })
        assertTrue(frame.drop(260).all { it == 0.toShort() })
    }

    @Test fun runReleasesOneFrameEvery20Milliseconds() =
        runTest {
            val uplink = PacedUplink(8_000)
            uplink.enqueue(PcmFrame(ShortArray(160 * 3) { 1 }, 8_000))
            val written = mutableListOf<ShortArray>()
            val job =
                launch {
                    uplink.run({ testScheduler.currentTime * 1_000_000 }) {
                        written += it
                        true
                    }
                }
            advanceTimeBy(1_000)
            runCurrent()
            job.cancel()
            assertEquals(51, written.size)
            assertTrue(written.take(3).all { frame -> frame.all { it == 1.toShort() } })
            assertArrayEquals(ShortArray(160), written[3])
        }

    @Test fun drainingCompletesWhenAudioPlaysIsCancelledOrTheCallStops() =
        runTest {
            for (ending in listOf("played", "cancelled", "stopped")) {
                val uplink = PacedUplink(8_000)
                uplink.enqueue(PcmFrame(ShortArray(320) { 1 }, 8_000))
                var stopped = false
                val drained = async { uplink.awaitDrained { stopped } }
                runCurrent()
                assertTrue(!drained.isCompleted)
                when (ending) {
                    "played" -> repeat(2) { uplink.nextFrame() }
                    "cancelled" -> uplink.cancel()
                    "stopped" -> stopped = true
                }
                advanceTimeBy(PacedUplink.FRAME_MILLIS)
                runCurrent()
                assertTrue(ending, drained.isCompleted)
                drained.await()
            }
        }

    @Test fun cancelDropsQueuedAudioAndFailsTonesStillWaiting() =
        runTest {
            val uplink = PacedUplink(8_000)
            val played = async { runCatching { uplink.playAndAwait(PacedUplink.dtmfTones("1", 8_000)) } }
            runCurrent()
            uplink.nextFrame()
            uplink.cancel()
            advanceTimeBy(PacedUplink.FRAME_MILLIS * 2)
            assertTrue(played.await().isFailure)
            assertArrayEquals(ShortArray(160), uplink.nextFrame())

            val finished = async { runCatching { uplink.playAndAwait(PacedUplink.dtmfTones("1", 8_000)) } }
            runCurrent()
            repeat(20) { uplink.nextFrame() }
            advanceTimeBy(PacedUplink.FRAME_MILLIS * 2)
            assertTrue(finished.await().isSuccess)
        }
}
