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
                "extra_call",
                "screen_content",
                "history",
                "revisions",
                "screen",
                "one_call",
                "invalid_call",
                "scroll_reversal",
                "scroll_end",
                "scrolled",
                "screenshot_limit",
                "result",
                "text_result",
                "text_verified",
                "screenshot_attached",
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

    @Test fun stopAtActionBoundaryDoesNotClaimAnUnsubmittedMutation() =
        runTest {
            val phone = phone()
            val agent = TextTaskAgent(phone, WorkerModel { reply("home") }, wording)
            val result = agent.run("goal") { if (it.phase == com.colonelpanic.eva.devicecontrol.TaskPhase.ACTING) agent.cancel() }
            assertEquals(TaskStatus.CANCELLED, result.status)
            assertEquals(0, agent.effects)
            assertTrue(phone.actions.isEmpty())
        }

    @Test fun backendFailureReportsItsCause() =
        runTest {
            val unreachable =
                object : com.colonelpanic.eva.devicecontrol.DeviceBackend {
                    override suspend fun observe(): Observation =
                        throw java.io.IOException("Portal did not respond on port 8080 (ConnectException)")

                    override suspend fun perform(action: com.colonelpanic.eva.devicecontrol.proto.Action): ActionResult = error("unused")
                }
            val result = TextTaskAgent(unreachable, WorkerModel { finish() }, wording).run("goal") {}
            assertEquals(TaskStatus.FAILED, result.status)
            assertEquals("worker_error: IOException: Portal did not respond on port 8080 (ConnectException)", result.summary)
        }

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

    @Test fun scrollFeedbackBoundsPreviewsAndIncludesResourceOnlyRows() =
        runTest {
            val requests = mutableListOf<WorkerRequest>()
            val paragraph = "A long paragraph about opening hours repeated many times ".repeat(20)
            val after =
                screen.copy(
                    observationId = "after",
                    elements =
                        listOf(
                            com.colonelpanic.eva.devicecontrol.proto.Element(
                                0,
                                com.colonelpanic.eva.devicecontrol.proto.Role.TEXT,
                                text = paragraph,
                                bounds =
                                    com.colonelpanic.eva.devicecontrol.proto
                                        .Bounds(0, 0, 100, 100),
                                depth = 0,
                            ),
                            com.colonelpanic.eva.devicecontrol.proto.Element(
                                1,
                                com.colonelpanic.eva.devicecontrol.proto.Role.SWITCH,
                                resourceId = "test:id/toggle",
                                checkable = true,
                                bounds =
                                    com.colonelpanic.eva.devicecontrol.proto
                                        .Bounds(0, 100, 100, 200),
                                depth = 0,
                            ),
                        ),
                )
            val phone =
                FakeDeviceBackend(screen) { action ->
                    ActionResult(action.actionId, action.kind, true, "now", "now", after, executionStatus = ExecutionStatus.EXECUTED)
                }
            val text = wording.copy(notices = wording.notices + ("scrolled" to "{count}: {preview}"))
            val agent =
                TextTaskAgent(
                    phone,
                    WorkerModel { request ->
                        requests += request
                        if (requests.size == 1) reply("scroll", """{"direction":"down","element":null,"intent":"Read"}""") else finish()
                    },
                    text,
                )
            assertEquals(TaskStatus.COMPLETED, agent.run("hours") {}.status)
            val feedback =
                requests
                    .last()
                    .messages
                    .single { it.resultFor != null }
                    .text
            assertTrue(feedback.contains("2:"))
            assertTrue(feedback.contains("toggle"))
            assertTrue(feedback.contains("…"))
            assertFalse(feedback.contains(paragraph.take(31)))
            assertTrue(feedback.length < 200)
        }

    @Test fun noCallRetainsAssistantOutputAndReminder() =
        runTest {
            val requests = mutableListOf<WorkerRequest>()
            val agent =
                TextTaskAgent(
                    phone(),
                    WorkerModel { request ->
                        requests += request
                        if (requests.size ==
                            1
                        ) {
                            WorkerReply(emptyList(), output = listOf(WorkerMessage("assistant", "checking")))
                        } else {
                            finish()
                        }
                    },
                    wording,
                )
            assertEquals(TaskStatus.COMPLETED, agent.run("goal") {}.status)
            assertTrue(requests.last().messages.any { it.role == "assistant" && it.text == "checking" })
            assertTrue(
                requests
                    .last()
                    .messages
                    .last()
                    .text
                    .contains("one_call"),
            )
        }

    @Test fun nativeExchangeRetainsFreshScreenAndSurvivesContextRebuild() =
        runTest {
            val requests = mutableListOf<WorkerRequest>()
            val after = screen.copy(observationId = "after", packageName = "com.android.settings")
            val phone =
                FakeDeviceBackend(screen) { action ->
                    ActionResult(action.actionId, action.kind, true, "now", "now", after, executionStatus = ExecutionStatus.EXECUTED)
                }
            val agent =
                TextTaskAgent(
                    phone,
                    WorkerModel { request ->
                        requests += request
                        if (requests.size == 1) reply("home") else finish()
                    },
                    wording,
                    WorkerSettings(maxScreens = 1),
                )
            assertEquals(TaskStatus.COMPLETED, agent.run("goal") {}.status)
            val messages = requests.last().messages
            val call = messages.single { it.call != null }
            val result = messages.single { it.resultFor != null }
            assertEquals(call.call!!.id, result.resultFor)
            assertTrue(result.text.contains("ok"))
            assertTrue(messages.last().text.contains("observation after"))
            assertEquals(1, phone.observations)
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
