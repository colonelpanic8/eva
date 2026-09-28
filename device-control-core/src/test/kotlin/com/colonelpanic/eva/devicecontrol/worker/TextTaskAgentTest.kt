@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.colonelpanic.eva.devicecontrol.worker

import com.colonelpanic.eva.devicecontrol.TaskStatus
import com.colonelpanic.eva.devicecontrol.proto.ActionResult
import com.colonelpanic.eva.devicecontrol.proto.ExecutionStatus
import com.colonelpanic.eva.devicecontrol.proto.Observation
import com.colonelpanic.eva.devicecontrol.proto.Orientation
import com.colonelpanic.eva.devicecontrol.proto.Screen
import com.colonelpanic.eva.devicecontrol.proto.kind
import com.colonelpanic.eva.devicecontrol.testing.FakeDeviceBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTaskAgentTest {
    private val screen = Observation("o1", "now", "fake", screen = Screen(100, 200, Orientation.PORTRAIT))
    private val wording =
        WorkerWording(
            "test",
            listOf(
                "task",
                "screen",
                "one_call",
                "invalid_call",
                "scroll_reversal",
                "scroll_end",
                "scrolled",
                "screenshot_limit",
                "result",
                "text_result",
            ).associateWith {
                "$it {goal} {revisions} {result}"
            },
            emptyList(),
        )

    private fun reply(
        name: String,
        args: String = "{}",
    ) = WorkerReply(listOf(WorkerCall("call", name, Json.parseToJsonElement(args).jsonObject)))

    private fun finish() = reply("finish", """{"status":"completed","summary":"done"}""")

    private fun phone() =
        FakeDeviceBackend(
            screen,
        ) { action -> ActionResult(action.actionId, action.kind, true, "now", "now", screen, executionStatus = ExecutionStatus.EXECUTED) }

    @Test fun stopCancelsInferenceBeforeAnyAction() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val phone = phone()
            val agent =
                TextTaskAgent(
                    phone,
                    WorkerModel {
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    },
                    wording,
                )
            val result = async { agent.run("goal") {} }
            entered.await()
            agent.cancel()
            assertTrue(agent.isStopped)
            assertEquals(TaskStatus.CANCELLED, result.await().status)
            assertTrue(cancelled.isCompleted)
            assertTrue(phone.actions.isEmpty())
        }

    @Test fun revisionCancelsObsoleteModelAndForcesFreshObservation() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val requests = mutableListOf<WorkerRequest>()
            val phone = phone()
            val agent =
                TextTaskAgent(
                    phone,
                    WorkerModel { request ->
                        requests += request
                        if (requests.size ==
                            1
                        ) {
                            entered.complete(Unit)
                            awaitCancellation()
                        } else {
                            finish()
                        }
                    },
                    wording,
                )
            val result = async { agent.run("goal") {} }
            entered.await()
            agent.revise("different goal")
            assertEquals(1L, agent.revision)
            assertEquals(TaskStatus.COMPLETED, result.await().status)
            assertEquals(2, phone.observations)
            assertTrue(
                requests
                    .last()
                    .messages
                    .first()
                    .text
                    .contains("different goal"),
            )
        }

    @Test fun askUserWaitsForMailboxWithoutFinishingTask() =
        runTest {
            var calls = 0
            val phone = phone()
            val agent =
                TextTaskAgent(
                    phone,
                    WorkerModel {
                        if (calls++ ==
                            0
                        ) {
                            reply("ask_user", """{"question":"Which branch?"}""")
                        } else {
                            finish()
                        }
                    },
                    wording,
                )
            val result = async { agent.run("hours") {} }
            runCurrent()
            assertFalse(result.isCompleted)
            assertTrue(phone.actions.isEmpty())
            agent.revise("Riverside")
            assertEquals(TaskStatus.COMPLETED, result.await().status)
        }

    @Test fun stopDrainsAnIssuedActionAndRetainsKnownPartialEffect() =
        runTest {
            val issued = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val phone =
                phone().apply {
                    onPerform =
                        { action ->
                            withContext(NonCancellable) {
                                issued.complete(Unit)
                                release.await()
                                ActionResult(
                                    action.actionId,
                                    action.kind,
                                    true,
                                    "now",
                                    "now",
                                    screen,
                                    executionStatus = ExecutionStatus.EXECUTED,
                                )
                            }
                        }
                }
            val agent = TextTaskAgent(phone, WorkerModel { reply("home") }, wording)
            val result = async { agent.run("goal") {} }
            issued.await()
            agent.cancel()
            runCurrent()
            assertFalse(result.isCompleted)
            release.complete(Unit)
            assertEquals(TaskStatus.CANCELLED, result.await().status)
            assertEquals(1, agent.effects)
            assertEquals(1, phone.actions.size)
        }

    @Test fun repeatedActionStopsBeforeThirdDispatchAndContextAppends() =
        runTest {
            val requests = mutableListOf<WorkerRequest>()
            val phone = phone()
            val agent =
                TextTaskAgent(
                    phone,
                    WorkerModel {
                        requests += it
                        reply("home")
                    },
                    wording,
                )
            val result = agent.run("goal") {}
            assertEquals("loop_detected", result.summary)
            assertEquals(2, phone.actions.size)
            assertEquals(requests[0].messages, requests[1].messages.take(requests[0].messages.size))
        }

    @Test fun multipleCallsNeverDispatchMultipleActions() =
        runTest {
            val phone = phone()
            val agent = TextTaskAgent(phone, WorkerModel { WorkerReply(reply("home").calls + reply("back").calls) }, wording)
            assertEquals(TaskStatus.FAILED, agent.run("goal") {}.status)
            assertEquals(2, phone.actions.size)
            assertTrue(phone.actions.all { it is com.colonelpanic.eva.devicecontrol.proto.Home })
        }

    @Test fun unknownPrimitiveDoesNotApplyOuterBarrierButTaskRemainsUnknown() =
        runTest {
            var calls = 0
            val phone =
                phone().apply {
                    onPerform =
                        { action ->
                            ActionResult(
                                action.actionId,
                                action.kind,
                                false,
                                "now",
                                "now",
                                screen,
                                executionStatus = ExecutionStatus.OUTCOME_UNKNOWN,
                            )
                        }
                }
            val agent =
                TextTaskAgent(
                    phone,
                    WorkerModel {
                        when (calls++) {
                            0 -> reply("home")
                            1 -> reply("back")
                            else -> finish()
                        }
                    },
                    wording,
                )
            assertEquals(TaskStatus.UNKNOWN, agent.run("goal") {}.status)
            assertEquals(2, phone.actions.size)
        }
}
