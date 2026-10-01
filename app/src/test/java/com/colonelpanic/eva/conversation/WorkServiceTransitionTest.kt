package com.colonelpanic.eva.conversation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkServiceTransitionTest {
    @Test fun promotionCompletesBeforeVoiceStopsAndWorkPersistsUntilJournalFinishes() =
        runTest {
            val promoted = CompletableDeferred<Boolean>()
            val events = mutableListOf<String>()
            val transition =
                WorkServiceTransition(
                    startWork = {
                        events += "request work"
                        promoted.await().also { events += "promoted" }
                    },
                    stopWork = { events += "stop work" },
                    startVoice = { events += "voice" },
                    stopVoice = { events += "stop voice" },
                )
            val starting = launch { transition.update(voice = true, work = false) }
            testScheduler.runCurrent()
            assertEquals(listOf("voice", "request work"), events)
            promoted.complete(true)
            starting.join()
            transition.update(voice = true, work = true)
            transition.update(voice = true, work = false)
            assertEquals(listOf("voice", "request work", "promoted"), events)
            transition.update(voice = false, work = true)
            assertEquals(listOf("voice", "request work", "promoted", "request work", "promoted", "stop voice"), events)
            transition.update(voice = false, work = false)
            assertEquals("stop work", events.last())
        }

    @Test fun refusedStartIsNotRepeatedPerUtteranceButRetriedBeforeVoiceEnds() =
        runTest {
            var starts = 0
            val events = mutableListOf<String>()
            val transition =
                WorkServiceTransition(
                    startWork = {
                        starts++
                        events += "attempt"
                        false
                    },
                    stopWork = { events += "stop work" },
                    startVoice = { events += "voice" },
                    stopVoice = { events += "stop voice" },
                )
            transition.update(true, false)
            repeat(3) {
                transition.update(true, true)
                transition.update(true, false)
            }
            assertEquals(1, starts)
            transition.update(false, true)
            assertEquals(2, starts)
            assertEquals(listOf("attempt", "stop voice"), events.takeLast(2))
            transition.update(false, false)
            assertEquals("stop work", events.last())
        }
}
