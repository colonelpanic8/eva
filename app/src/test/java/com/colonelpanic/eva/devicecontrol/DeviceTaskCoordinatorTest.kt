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

    @Test fun taskHoldsLeaseUntilTerminalAndControlsBypassItsPendingCall() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            var requests = 0
            val coordinator =
                DeviceTaskCoordinator {
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
            val result = async { coordinator.execute(proposal()) }
            entered.await()
            assertFalse(result.isCompleted)
            assertEquals(InvocationStatus.NOT_EXECUTED, coordinator.execute(proposal("other")).status)
            var ordinaryRan = false
            val ordinary =
                object : ExecutionBackend {
                    override suspend fun unavailableReason(): String? = null

                    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                        ordinaryRan = true
                        return ExecutionOutcome(InvocationStatus.COMPLETED, "done")
                    }
                }
            assertEquals(
                InvocationStatus.NOT_EXECUTED,
                coordinator.executeAdmitted(proposal().copy(capabilityId = CapabilityRegistry.OPEN_APP), ordinary, true).status,
            )
            assertFalse(ordinaryRan)
            assertTrue(coordinator.revise("thread", "corrected"))
            assertEquals(
                1L,
                coordinator.running.value!!
                    .agent.revision,
            )
            assertEquals(InvocationStatus.COMPLETED, result.await().status)
            assertNull(coordinator.lease.owner)
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
