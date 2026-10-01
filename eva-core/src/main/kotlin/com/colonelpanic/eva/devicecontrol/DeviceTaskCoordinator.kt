package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.capability.ActionInitiator
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InitiatorKind
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.devicecontrol.worker.TextTaskAgent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    private val admittedOwners = mutableMapOf<String, String?>()
    private val mutableWaitingForLease = MutableStateFlow<Set<String>>(emptySet())
    val waitingForLease = mutableWaitingForLease.asStateFlow()

    data class Running(
        val threadId: String,
        val turnId: String,
        val agent: TextTaskAgent,
        val progress: TaskProgress? = null,
        val callId: String,
        val job: kotlinx.coroutines.CompletableJob,
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

    fun stop(turnId: String? = null): Boolean =
        synchronized(monitor) {
            if (turnId != null) stoppedTurns += turnId
            val task = mutableRunning.value?.takeIf { turnId == null || it.turnId == turnId } ?: return false
            task.agent.cancel()
            true
        }

    /** Stops the task running for [threadId] without latching its turn, so actions queued behind it still run. */
    fun stopRunning(threadId: String): Boolean =
        synchronized(monitor) {
            val task = mutableRunning.value?.takeIf { it.threadId == threadId } ?: return false
            task.agent.cancel()
            true
        }

    fun forceStop(turnId: String) =
        synchronized(monitor) {
            stoppedTurns += turnId
            admittedOwners.filterValues { it == turnId }.keys.forEach(lease::releaseIfOwned)
            val task = mutableRunning.value?.takeIf { it.turnId == turnId }
            if (task != null) {
                task.agent.cancel()
                task.job.cancel()
                mutableRunning.value = null
                lease.releaseIfOwned(task.callId)
            }
        }

    override suspend fun unavailableReason(): String? = unavailable()

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome =
        ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "A device task needs an owning conversation turn.")

    override suspend fun execute(proposal: ToolProposal): ExecutionOutcome {
        val thread = proposal.threadId ?: return execute(proposal.arguments)
        val turn = proposal.turnId ?: return execute(proposal.arguments)
        if (synchronized(monitor) { turn in stoppedTurns }) return stoppedBeforeDispatch()
        awaitLease(proposal.callId)?.let { return it }
        val agent =
            synchronized(monitor) {
                if (turn in stoppedTurns) {
                    lease.release(proposal.callId)
                    return stoppedBeforeDispatch()
                }
                try {
                    create().also { mutableRunning.value = Running(thread, turn, it, callId = proposal.callId, job = Job()) }
                } catch (
                    e: Exception,
                ) {
                    lease.release(proposal.callId)
                    return ExecutionOutcome(
                        InvocationStatus.NOT_EXECUTED,
                        "Device-task setup is unavailable: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        // Only the agent's explicit controls end a task; an attachment cannot release its lease.
        val taskJob =
            synchronized(monitor) { mutableRunning.value?.takeIf { it.agent === agent }?.job }
                ?: return stoppedBeforeDispatch()
        return withContext(taskJob) {
            try {
                val result =
                    agent.run(proposal.arguments.getValue("goal")) { progress ->
                        synchronized(monitor) {
                            mutableRunning.value?.takeIf { it.agent === agent }?.let { mutableRunning.value = it.copy(progress = progress) }
                        }
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
                                        step.callId?.let { put("callId", it) }
                                        put(
                                            "initiator",
                                            ActionInitiator(
                                                InitiatorKind.DEVICE_TASK_WORKER,
                                                inputId = proposal.initiator?.inputId,
                                                responseId = step.responseId,
                                                legId = result.taskId,
                                                parentResponseId = proposal.initiator?.responseId,
                                                outputItemId = step.outputItemId,
                                            ).toJson(),
                                        )
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
                    if (mutableRunning.value?.agent === agent) mutableRunning.value = null
                    lease.releaseIfOwned(proposal.callId)
                    taskJob.complete()
                }
            }
        }
    }

    private fun stoppedBeforeDispatch() = ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "Stopped before device-task dispatch.")

    /**
     * Queues behind whatever holds the device. Cancellation while queued is reported as not run
     * rather than propagated, because the dispatcher would otherwise journal it as uncertain.
     */
    private suspend fun awaitLease(callId: String): ExecutionOutcome? {
        synchronized(monitor) { mutableWaitingForLease.value += callId }
        return try {
            lease.acquire(callId)
            null
        } catch (_: CancellationException) {
            ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "Cancelled while waiting for an earlier device action to finish.")
        } finally {
            synchronized(monitor) { mutableWaitingForLease.value -= callId }
        }
    }

    suspend fun executeAdmitted(
        proposal: ToolProposal,
        backend: ExecutionBackend,
        needsDevice: Boolean,
    ): ExecutionOutcome {
        if (proposal.capabilityId == CapabilityRegistry.DEVICE_TASK || !needsDevice) return backend.execute(proposal)
        if (synchronized(monitor) { proposal.turnId in stoppedTurns }) return stoppedBeforeDispatch()
        awaitLease(proposal.callId)?.let { return it }
        synchronized(monitor) {
            if (proposal.turnId in stoppedTurns) {
                lease.releaseIfOwned(proposal.callId)
                return stoppedBeforeDispatch()
            }
            admittedOwners[proposal.callId] = proposal.turnId
        }
        return try {
            backend.execute(proposal)
        } finally {
            synchronized(monitor) {
                admittedOwners.remove(proposal.callId)
                lease.releaseIfOwned(proposal.callId)
            }
        }
    }
}
