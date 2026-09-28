package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.devicecontrol.worker.TextTaskAgent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Application ownership survives provider/audio attachment changes. */
class DeviceTaskCoordinator(
    private val unavailable: suspend () -> String? = { null },
    private val create: () -> TextTaskAgent,
) : ExecutionBackend {
    private val monitor = Any()
    val lease = DeviceExecutionLease()
    private val stoppedTurns = mutableSetOf<String>()

    data class Running(
        val threadId: String,
        val turnId: String,
        val agent: TextTaskAgent,
        val progress: TaskProgress? = null,
    )

    private val mutableRunning = MutableStateFlow<Running?>(null)
    val running = mutableRunning.asStateFlow()

    fun owns(turnId: String) = running.value?.turnId == turnId

    fun revise(
        threadId: String,
        text: String,
    ): Boolean =
        synchronized(monitor) {
            val task = mutableRunning.value?.takeIf { it.threadId == threadId } ?: return false
            task.agent.revise(text)
            true
        }

    fun pause(threadId: String) =
        synchronized(monitor) {
            mutableRunning.value
                ?.takeIf { it.threadId == threadId }
                ?.agent
                ?.pauseForCorrection()
            Unit
        }

    fun stop(turnId: String? = null): Boolean =
        synchronized(monitor) {
            if (turnId != null) stoppedTurns += turnId
            val task = mutableRunning.value?.takeIf { turnId == null || it.turnId == turnId } ?: return false
            task.agent.cancel()
            true
        }

    override suspend fun unavailableReason(): String? = unavailable()

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome =
        ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "A device task needs an owning conversation turn.")

    override suspend fun execute(proposal: ToolProposal): ExecutionOutcome {
        val thread = proposal.threadId ?: return execute(proposal.arguments)
        val turn = proposal.turnId ?: return execute(proposal.arguments)
        val agent =
            synchronized(monitor) {
                if (turn in stoppedTurns) return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "Stopped before device-task dispatch.")
                if (!lease.acquire(
                        proposal.callId,
                    )
                ) {
                    return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "Another device operation is running.")
                }
                try {
                    create().also { mutableRunning.value = Running(thread, turn, it) }
                } catch (
                    e: Exception,
                ) {
                    lease.release(proposal.callId)
                    return ExecutionOutcome(
                        InvocationStatus.NOT_EXECUTED,
                        "Device-task setup is unavailable. Check backend and model credentials.",
                    )
                }
            }
        // Only the agent's explicit controls end a task; an attachment cannot release its lease.
        return withContext(NonCancellable) {
            try {
                val result =
                    agent.run(proposal.arguments.getValue("goal")) { progress ->
                        synchronized(monitor) { mutableRunning.value = mutableRunning.value?.copy(progress = progress) }
                    }
                val status =
                    when (result.status) {
                        TaskStatus.COMPLETED -> InvocationStatus.COMPLETED
                        TaskStatus.UNKNOWN -> InvocationStatus.UNKNOWN
                        TaskStatus.CANCELLED -> if (agent.effects == 0) InvocationStatus.NOT_EXECUTED else InvocationStatus.FAILED
                        TaskStatus.FAILED -> InvocationStatus.FAILED
                    }
                ExecutionOutcome(
                    status,
                    when {
                        status == InvocationStatus.UNKNOWN -> {
                            "The task ended with an unresolved device action. " +
                                "Check the current screen before continuing."
                        }

                        result.status == TaskStatus.CANCELLED && agent.effects > 0 -> {
                            "Stopped after ${agent.effects} device actions. Earlier changes may remain."
                        }

                        result.status == TaskStatus.CANCELLED -> {
                            "Stopped before any device action completed."
                        }

                        else -> {
                            result.summary
                        }
                    },
                    buildJsonObject {
                        put("taskId", result.taskId)
                        put("revision", result.revision)
                        put("status", result.status.name)
                        put("effects", agent.effects)
                        put(
                            "steps",
                            JsonArray(
                                agent.steps.map { step ->
                                    buildJsonObject {
                                        put("step", step.step)
                                        put("revision", step.revision)
                                        put("kind", step.kind)
                                        put("result", step.result)
                                        put("observationMillis", step.timing.observationMillis)
                                        put("modelMillis", step.timing.modelMillis)
                                        put("actionMillis", step.timing.actionMillis)
                                    }
                                },
                            ),
                        )
                    },
                )
            } finally {
                synchronized(monitor) {
                    mutableRunning.value = null
                    lease.release(proposal.callId)
                }
            }
        }
    }

    suspend fun executeAdmitted(
        proposal: ToolProposal,
        backend: ExecutionBackend,
        needsDevice: Boolean,
    ): ExecutionOutcome {
        if (proposal.capabilityId == CapabilityRegistry.DEVICE_TASK || !needsDevice) return backend.execute(proposal)
        if (!lease.acquire(
                proposal.callId,
            )
        ) {
            return ExecutionOutcome(
                InvocationStatus.NOT_EXECUTED,
                "A device task is running. Stop it before using other device controls.",
            )
        }
        return try {
            backend.execute(proposal)
        } finally {
            lease.release(proposal.callId)
        }
    }
}
