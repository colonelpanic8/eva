package com.colonelpanic.eva.capability

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BoundedExecutionTest {
    @Test
    fun `voice cue expiry a turn that never comes and late results do not resubmit work`() =
        runTest {
            var cues = 0
            var calls = 0
            var late = 0
            val execution = BoundedExecution(backgroundScope) { _, _ -> late++ }
            val proposal = ToolProposal("one", "test", emptyMap(), "test", "rev", onWaiting = { cues++ })
            val budget = WaitBudget(InteractionMode.VOICE, 20_000, null, 1000)
            val first =
                async {
                    execution.execute("instance", proposal, budget) {
                        calls++
                        delay(2000)
                        ExecutionOutcome(InvocationStatus.COMPLETED, "Done")
                    }
                }
            runCurrent()
            val busy =
                async { execution.execute("instance", proposal.copy(callId = "two", onWaiting = {}), budget) { error("must not run") } }
            advanceTimeBy(500)
            runCurrent()
            assertEquals(1, cues)
            advanceTimeBy(500)
            runCurrent()
            assertEquals(InvocationStatus.UNKNOWN, first.await().status)
            assertEquals(InvocationStatus.NOT_EXECUTED, busy.await().status)
            assertEquals(1, calls)
            assertEquals(0, late)
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(1, late)
            assertEquals(
                InvocationStatus.COMPLETED,
                execution
                    .execute("instance", proposal, budget) {
                        ExecutionOutcome(InvocationStatus.COMPLETED, "Next")
                    }.status,
            )
        }

    @Test
    fun `four instances run concurrently and cancelling a waiter does not undo submitted work`() =
        runTest {
            val execution = BoundedExecution(backgroundScope)
            val proposal = ToolProposal("call", "test", emptyMap(), "test", "rev")
            val budget = WaitBudget(InteractionMode.TYPED, 30_000, null, null)
            var finished = 0
            val jobs =
                (1..4).map { instance ->
                    async {
                        execution.execute("instance$instance", proposal, budget) {
                            delay(1000)
                            finished++
                            ExecutionOutcome(InvocationStatus.COMPLETED, "Done")
                        }
                    }
                }
            runCurrent()
            var fifthStarted = false
            val fifth =
                async {
                    execution.execute("fifth", proposal, budget) {
                        fifthStarted = true
                        ExecutionOutcome(InvocationStatus.COMPLETED, "Fifth")
                    }
                }
            runCurrent()
            assertFalse(fifthStarted)
            jobs.first().cancel()
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(4, finished)
            jobs.drop(1).forEach { assertEquals(InvocationStatus.COMPLETED, it.await().status) }
            assertEquals(InvocationStatus.COMPLETED, fifth.await().status)
        }

    @Test
    fun `a call for a busy instance waits its turn and then runs`() =
        runTest {
            val execution = BoundedExecution(backgroundScope)
            val proposal = ToolProposal("call", "test", emptyMap(), "test", "rev")
            val budget = WaitBudget(InteractionMode.TYPED, 30_000, null, null)
            val order = mutableListOf<String>()
            val first =
                async {
                    execution.execute("instance", proposal, budget) {
                        delay(1000)
                        order += "first"
                        ExecutionOutcome(InvocationStatus.COMPLETED, "First")
                    }
                }
            runCurrent()
            val second =
                async {
                    execution.execute("instance", proposal.copy(callId = "two"), budget) {
                        order += "second"
                        ExecutionOutcome(InvocationStatus.COMPLETED, "Second")
                    }
                }
            advanceTimeBy(1001)
            runCurrent()
            assertEquals(InvocationStatus.COMPLETED, first.await().status)
            assertEquals(InvocationStatus.COMPLETED, second.await().status)
            assertEquals(listOf("first", "second"), order)
        }
}
