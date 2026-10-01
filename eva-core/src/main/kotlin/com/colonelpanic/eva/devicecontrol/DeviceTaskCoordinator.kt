package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.capability.ActionInitiator
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InitiatorKind
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.conversation.prompt.Wording
import com.colonelpanic.eva.devicecontrol.worker.TextTaskAgent
import com.colonelpanic.eva.diagnostics.EvaTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Application ownership survives provider/audio attachment changes. */
class DeviceTaskCoordinator(
    private val unavailable: suspend () -> String? = { null },
    private val releaseScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val wording: () -> Wording = { Wording.bundled },
    private val create: () -> TextTaskAgent,
) : ExecutionBackend {
    private val monitor = Any()
    val lease = DeviceExecutionLease()
    private val stoppedTurns = mutableSetOf<String>()
    private val admittedOwners = mutableMapOf<String, String?>()
    private val mutableWaitingForLease = MutableStateFlow<Set<String>>(emptySet())
    val waitingForLease = mutableWaitingForLease.asStateFlow()

    private val releaseJobs = mutableMapOf<String, Job>()
    private val mutableReleasing = MutableStateFlow<Set<String>>(emptySet())
    val releasing = mutableReleasing.asStateFlow()
    private var uncertainScreen = false
    private var screenEpoch = 0L

    private fun release(callId: String) {
        releaseJobs.remove(callId)?.cancel()
        admittedOwners.remove(callId)?.let { mutableReleasing.value -= it }
        lease.releaseIfOwned(callId)
    }

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
            mutableRunning.value?.takeIf { it.turnId == turnId }?.let {
                it.agent.cancel()
                it.job.cancel()
            }
            admittedOwners.filterValues { it == turnId }.keys.toList().forEach { callId ->
                if (callId !in releaseJobs) {
                    mutableReleasing.value += turnId
                    releaseJobs[callId] =
                        releaseScope.launch {
                            delay(10_000)
                            synchronized(monitor) {
                                if (lease.owner == callId) {
                                    uncertainScreen = true
                                    screenEpoch++
                                    if (mutableRunning.value?.callId == callId) mutableRunning.value = null
                                    release(callId)
                                }
                            }
                        }
                }
            }
        }

    override suspend fun unavailableReason(): String? = unavailable()

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome =
        ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "A device task needs an owning conversation turn.")

    override suspend fun execute(proposal: ToolProposal): ExecutionOutcome {
        val thread = proposal.threadId ?: return execute(proposal.arguments)
        val turn = proposal.turnId ?: return execute(proposal.arguments)
        if (synchronized(monitor) { turn in stoppedTurns }) return stoppedBeforeDispatch()
        awaitLease(proposal)?.let { return it }
        val agent =
            synchronized(monitor) {
                if (turn in stoppedTurns) {
                    lease.release(proposal.callId)
                    return stoppedBeforeDispatch()
                }
                try {
                    create().also {
                        admittedOwners[proposal.callId] = turn
                        mutableRunning.value = Running(thread, turn, it, callId = proposal.callId, job = Job())
                    }
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
        val epoch = synchronized(monitor) { screenEpoch }
        EvaTrace.info("device_task.started", "call" to proposal.callId, "turn" to turn, "uncertainScreen" to uncertainScreen.takeIf { it })
        val goal =
            synchronized(monitor) {
                if (uncertainScreen) {
                    wording().message(Wording.DEVICE_RELEASE_UNCERTAIN) + "\n" + proposal.arguments.getValue("goal")
                } else {
                    proposal.arguments.getValue("goal")
                }
            }
        return withContext(taskJob) {
            try {
                val result =
                    agent.run(goal) { progress ->
                        synchronized(monitor) {
                            mutableRunning.value?.takeIf { it.agent === agent }?.let { mutableRunning.value = it.copy(progress = progress) }
                        }
                    }
                synchronized(monitor) { if (screenEpoch == epoch && result.status == TaskStatus.COMPLETED) uncertainScreen = false }
                EvaTrace.info(
                    "device_task.ended",
                    "call" to proposal.callId,
                    "turn" to turn,
                    "status" to result.status,
                    "effects" to agent.effects,
                    "steps" to agent.steps.size,
                )
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
                EvaTrace.info("device_task.released", "call" to proposal.callId, "turn" to turn)
                synchronized(monitor) {
                    if (mutableRunning.value?.agent === agent) mutableRunning.value = null
                    release(proposal.callId)
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
    private suspend fun awaitLease(proposal: ToolProposal): ExecutionOutcome? {
        val callId = proposal.callId
        synchronized(monitor) { mutableWaitingForLease.value += callId }
        return try {
            if (lease.owner != null) {
                EvaTrace.info("device.queued", "call" to callId, "capability" to proposal.capabilityId, "turn" to proposal.turnId)
                proposal.onQueued()
            }
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
        awaitLease(proposal)?.let { return it }
        synchronized(monitor) {
            if (proposal.turnId in stoppedTurns) {
                lease.releaseIfOwned(proposal.callId)
                return stoppedBeforeDispatch()
            }
            if (uncertainScreen && proposal.capabilityId != CapabilityRegistry.UI_OBSERVE) {
                lease.releaseIfOwned(proposal.callId)
                return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, wording().message(Wording.DEVICE_RELEASE_UNCERTAIN))
            }
            admittedOwners[proposal.callId] = proposal.turnId
        }
        val epoch = synchronized(monitor) { screenEpoch }
        return try {
            backend.execute(proposal).also {
                if (proposal.capabilityId == CapabilityRegistry.UI_OBSERVE && it.status == InvocationStatus.COMPLETED) {
                    synchronized(monitor) { if (screenEpoch == epoch) uncertainScreen = false }
                }
            }
        } finally {
            synchronized(monitor) {
                release(proposal.callId)
            }
        }
    }
}
