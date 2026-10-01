@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.capability.ActionInitiator
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InitiatorKind
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.conversation.prompt.Wording
import com.colonelpanic.eva.devicecontrol.proto.Action
import com.colonelpanic.eva.devicecontrol.proto.Observation
import com.colonelpanic.eva.devicecontrol.proto.Orientation
import com.colonelpanic.eva.devicecontrol.proto.Screen
import com.colonelpanic.eva.devicecontrol.worker.TextTaskAgent
import com.colonelpanic.eva.devicecontrol.worker.WorkerCall
import com.colonelpanic.eva.devicecontrol.worker.WorkerModel
import com.colonelpanic.eva.devicecontrol.worker.WorkerReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTaskCoordinatorTest {
    private val phone =
        object : DeviceBackend {
            override suspend fun observe() = Observation("o", "now", "fake", screen = Screen(100, 200, Orientation.PORTRAIT))

            override suspend fun perform(action: Action) = error("No action expected")
        }

    private fun proposal(id: String = "call") =
        ToolProposal(id, CapabilityRegistry.DEVICE_TASK, mapOf("goal" to "goal"), "goal", "catalog", "thread", "turn")

    private fun finishing(entered: CompletableDeferred<Unit>): () -> TextTaskAgent {
        var requests = 0
        return {
            TextTaskAgent(
                phone,
                WorkerModel {
                    if (requests++ == 0) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                    WorkerReply(
                        listOf(
                            WorkerCall(
                                "finish",
                                "finish",
                                Json.parseToJsonElement("""{"status":"completed","summary":"done"}""").jsonObject,
                            ),
                        ),
                    )
                },
                workerWording(Wording.bundled),
            )
        }
    }

    @Test fun workerStepsRetainTheirOwnOriginInsideTheParentReceipt() =
        runTest {
            val coordinator =
                DeviceTaskCoordinator(create = {
                    TextTaskAgent(
                        phone,
                        WorkerModel {
                            WorkerReply(
                                listOf(
                                    WorkerCall(
                                        "finish-call",
                                        "finish",
                                        Json.parseToJsonElement("""{"status":"completed","summary":"done"}""").jsonObject,
                                        responseId = "worker-response",
                                        outputItemId = "worker-output",
                                    ),
                                ),
                            )
                        },
                        workerWording(Wording.bundled),
                    )
                })
            val outcome =
                coordinator.execute(
                    proposal().copy(
                        initiator = ActionInitiator(InitiatorKind.USER_SPEECH, inputId = "speech", responseId = "parent-response"),
                    ),
                )
            assertEquals(InvocationStatus.COMPLETED, outcome.status)
            val step =
                outcome.data!!
                    .getValue("steps")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("finish-call", step.getValue("callId").jsonPrimitive.content)
            val origin = ActionInitiator.fromJson(step.getValue("initiator").jsonObject)
            assertEquals(InitiatorKind.DEVICE_TASK_WORKER, origin.kind)
            assertEquals("worker-response", origin.responseId)
            assertEquals("parent-response", origin.parentResponseId)
            assertEquals("worker-output", origin.outputItemId)
            assertEquals(
                outcome.data!!
                    .getValue("taskId")
                    .jsonPrimitive.content,
                origin.legId,
            )
        }

    @Test fun forcedOrdinaryActionCleanupCannotReleaseASuccessorsLease() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val coordinator = DeviceTaskCoordinator(releaseScope = backgroundScope) { error("No device worker expected") }
            val backend =
                object : ExecutionBackend {
                    override suspend fun unavailableReason(): String? = null

                    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                        entered.complete(Unit)
                        finish.await()
                        return ExecutionOutcome(InvocationStatus.UNKNOWN, "Effects uncertain")
                    }
                }
            val task =
                launch {
                    coordinator.executeAdmitted(proposal("old").copy(capabilityId = CapabilityRegistry.OPEN_APP), backend, true)
                }
            entered.await()
            coordinator.forceStop("turn")
            val successor = async { coordinator.lease.acquire("successor") }
            testScheduler.runCurrent()
            assertFalse(successor.isCompleted)
            assertEquals("old", coordinator.lease.owner)
            assertEquals(setOf("turn"), coordinator.releasing.value)
            finish.complete(Unit)
            task.join()
            successor.await()
            assertTrue(coordinator.releasing.value.isEmpty())
            assertEquals("successor", coordinator.lease.owner)
            coordinator.lease.release("successor")
            assertEquals(
                InvocationStatus.NOT_EXECUTED,
                coordinator.executeAdmitted(proposal("late").copy(capabilityId = CapabilityRegistry.OPEN_APP), backend, true).status,
            )
        }

    @Test fun expiredReleaseRequiresObservationAndLateCleanupCannotUnlockSuccessor() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val coordinator = DeviceTaskCoordinator(releaseScope = backgroundScope) { error("No worker") }
            val hung =
                object : ExecutionBackend {
                    override suspend fun unavailableReason(): String? = null

                    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                        entered.complete(Unit)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { finish.await() }
                        return ExecutionOutcome(InvocationStatus.COMPLETED, "late")
                    }
                }
            var nextExecutions = 0
            val next =
                object : ExecutionBackend {
                    override suspend fun unavailableReason(): String? = null

                    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                        nextExecutions++
                        return ExecutionOutcome(InvocationStatus.COMPLETED, "observed")
                    }
                }
            val old = launch { coordinator.executeAdmitted(proposal("old").copy(capabilityId = CapabilityRegistry.OPEN_APP), hung, true) }
            entered.await()
            coordinator.forceStop("turn")
            old.cancel()
            val successor =
                async {
                    coordinator.executeAdmitted(
                        proposal("next").copy(turnId = "next-turn", capabilityId = CapabilityRegistry.UI_TAP),
                        next,
                        true,
                    )
                }
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(9_999)
            assertFalse(successor.isCompleted)
            testScheduler.advanceTimeBy(1)
            testScheduler.runCurrent()
            val refusal = successor.await()
            assertEquals(InvocationStatus.NOT_EXECUTED, refusal.status)
            assertTrue(refusal.message.contains("screen is uncertain"))
            assertEquals(0, nextExecutions)
            coordinator.executeAdmitted(
                proposal("observe").copy(turnId = "next-turn", capabilityId = CapabilityRegistry.UI_OBSERVE),
                next,
                true,
            )
            coordinator.executeAdmitted(proposal("tap").copy(turnId = "next-turn", capabilityId = CapabilityRegistry.UI_TAP), next, true)
            assertEquals(2, nextExecutions)
            coordinator.lease.acquire("successor")
            finish.complete(Unit)
            old.join()
            assertEquals("successor", coordinator.lease.owner)
            coordinator.lease.release("successor")
        }

    @Test fun forceStopReleasesLeaseAfterWorkerReturnsAndDoesNotReplay() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            var creates = 0
            val coordinator =
                DeviceTaskCoordinator {
                    creates++
                    finishing(entered)()
                }
            val task = launch { coordinator.execute(proposal()) }
            entered.await()
            assertEquals("call", coordinator.lease.owner)
            coordinator.forceStop("turn")
            task.join()
            assertNull(coordinator.lease.owner)
            assertNull(coordinator.running.value)
            assertEquals(InvocationStatus.NOT_EXECUTED, coordinator.execute(proposal("late")).status)
            assertEquals(1, creates)
        }

    @Test fun actionsQueueBehindARunningTaskAndControlsBypassIt() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val coordinator = DeviceTaskCoordinator(create = finishing(entered))
            val result = async { coordinator.execute(proposal()) }
            entered.await()
            val order = mutableListOf<String>()
            val ordinary =
                object : ExecutionBackend {
                    override suspend fun unavailableReason(): String? = null

                    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                        order += "ordinary"
                        assertNull(coordinator.running.value)
                        return ExecutionOutcome(InvocationStatus.COMPLETED, "done")
                    }
                }
            val queued =
                async { coordinator.executeAdmitted(proposal("ordinary").copy(capabilityId = CapabilityRegistry.OPEN_APP), ordinary, true) }
            var abandonedOutcome: ExecutionOutcome? = null
            val abandoned =
                launch {
                    abandonedOutcome =
                        coordinator.executeAdmitted(
                            proposal("abandoned").copy(capabilityId = CapabilityRegistry.OPEN_APP),
                            ordinary,
                            true,
                        )
                }
            testScheduler.runCurrent()
            assertFalse(queued.isCompleted)
            abandoned.cancel()
            testScheduler.runCurrent()
            // Returned rather than thrown, so the dispatcher journals it as not run instead of uncertain.
            assertEquals(InvocationStatus.NOT_EXECUTED, abandonedOutcome?.status)
            assertEquals(emptyList<String>(), order)
            assertTrue(coordinator.revise("thread", "corrected"))
            assertEquals(
                1L,
                coordinator.running.value!!
                    .agent.revision,
            )
            assertEquals(InvocationStatus.COMPLETED, result.await().status)
            assertEquals(InvocationStatus.COMPLETED, queued.await().status)
            assertEquals(listOf("ordinary"), order)
            assertNull(coordinator.lease.owner)
        }

    @Test fun aSecondTaskRunsAfterTheFirstIsStopped() =
        runTest {
            val first = CompletableDeferred<Unit>()
            val second = CompletableDeferred<Unit>()
            var created = 0
            val coordinator =
                DeviceTaskCoordinator {
                    finishing(if (created++ == 0) first else second)()
                }
            val firstResult = async { coordinator.execute(proposal("first")) }
            first.await()
            val secondResult = async { coordinator.execute(proposal("second")) }
            testScheduler.runCurrent()
            assertEquals(1, created)
            assertTrue(coordinator.stopRunning("thread"))
            assertEquals(InvocationStatus.NOT_EXECUTED, firstResult.await().status)
            second.await()
            assertTrue(coordinator.revise("thread", "go on"))
            assertEquals(InvocationStatus.COMPLETED, secondResult.await().status)
        }

    @Test fun stopBeforeAdmissionLatchesAndCannotStartWorkerLater() =
        runTest {
            val coordinator = DeviceTaskCoordinator { error("Must not create a stopped task") }
            coordinator.stop("turn")
            assertEquals(InvocationStatus.NOT_EXECUTED, coordinator.execute(proposal()).status)
        }

    @Test fun stopWhileModelPendingReturnsNotExecutedOnlyWhenNoEffects() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val coordinator =
                DeviceTaskCoordinator {
                    TextTaskAgent(
                        phone,
                        WorkerModel {
                            entered.complete(Unit)
                            awaitCancellation()
                        },
                        workerWording(Wording.bundled),
                    )
                }
            val result = async { coordinator.execute(proposal()) }
            entered.await()
            coordinator.stop()
            assertTrue(
                coordinator.running.value!!
                    .agent.isStopped,
            )
            assertEquals(InvocationStatus.NOT_EXECUTED, result.await().status)
            assertNull(coordinator.lease.owner)
        }
}
