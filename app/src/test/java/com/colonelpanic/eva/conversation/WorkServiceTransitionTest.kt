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
            transition.update(voice = true, work = false)
            val ending = launch { transition.update(voice = false, work = true) }
            testScheduler.runCurrent()
            assertEquals(listOf("voice", "request work"), events)
            promoted.complete(true)
            ending.join()
            assertEquals(listOf("voice", "request work", "promoted", "stop voice"), events)
            transition.update(voice = false, work = false)
            assertEquals("stop work", events.last())
        }
}
