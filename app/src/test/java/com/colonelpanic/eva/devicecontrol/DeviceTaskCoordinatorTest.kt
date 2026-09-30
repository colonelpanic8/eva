@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
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
import kotlinx.serialization.json.jsonObject
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
