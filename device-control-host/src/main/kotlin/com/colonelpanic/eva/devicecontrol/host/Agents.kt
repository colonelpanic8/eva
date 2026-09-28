package com.colonelpanic.eva.devicecontrol.host

import com.colonelpanic.eva.devicecontrol.DeviceBackend
import com.colonelpanic.eva.devicecontrol.StepTiming
import com.colonelpanic.eva.devicecontrol.TaskAgent
import com.colonelpanic.eva.devicecontrol.TaskPhase
import com.colonelpanic.eva.devicecontrol.TaskProgress
import com.colonelpanic.eva.devicecontrol.TaskResult
import com.colonelpanic.eva.devicecontrol.TaskStatus
import com.colonelpanic.eva.devicecontrol.proto.ExecutionStatus
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import java.util.ServiceLoader
import java.util.UUID

/** Host providers inject the shared worker and model client through this JVM-only boundary. */
interface WorkerAgentFactory {
    fun create(
        backend: DeviceBackend,
        case: EvalCase,
    ): TaskAgent
}

fun workerFactory(): WorkerAgentFactory =
    ServiceLoader.load(WorkerAgentFactory::class.java).toList().singleOrNull()
        ?: error(
            "--agent worker unavailable: install exactly one WorkerAgentFactory service provider " +
                "backed by the core worker and a JVM model client",
        )

class NoopAgent : TaskAgent {
    override suspend fun run(
        goal: String,
        onProgress: (TaskProgress) -> Unit,
    ) = TaskResult("noop", 0, TaskStatus.COMPLETED, "", 0)

    override fun revise(correction: String) = error("noop does not accept corrections")

    override fun cancel() = Unit
}

class ScriptedAgent(
    private val backend: DeviceBackend,
    private val script: JsonObject,
    private val maxSteps: Int,
) : TaskAgent {
    private var cancelled = false

    override suspend fun run(
        goal: String,
        onProgress: (TaskProgress) -> Unit,
    ): TaskResult {
        val actions = script.objects("actions")
        require(actions.size <= maxSteps) { "Script exceeds max_steps" }
        val taskId = UUID.randomUUID().toString()
        for ((index, template) in actions.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (cancelled) return TaskResult(taskId, 0, TaskStatus.CANCELLED, "", index)
            onProgress(TaskProgress(taskId, 0, index + 1, TaskPhase.OBSERVING))
            val beforeObservation = System.nanoTime()
            val action = bindAction(backend, template, taskId)
            val observationMillis = (System.nanoTime() - beforeObservation) / 1_000_000
            if (cancelled) return TaskResult(taskId, 0, TaskStatus.CANCELLED, "", index)
            onProgress(TaskProgress(taskId, 0, index + 1, TaskPhase.ACTING))
            val beforeAction = System.nanoTime()
            val result = backend.perform(action)
            onProgress(
                TaskProgress(
                    taskId,
                    0,
                    index + 1,
                    TaskPhase.PROGRESS,
                    timing =
                        StepTiming(
                            observationMillis = observationMillis,
                            actionMillis = (System.nanoTime() - beforeAction) / 1_000_000,
                        ),
                ),
            )
            if (!result.ok || result.executionStatus == ExecutionStatus.OUTCOME_UNKNOWN) {
                val status = if (result.executionStatus == ExecutionStatus.OUTCOME_UNKNOWN) TaskStatus.UNKNOWN else TaskStatus.FAILED
                return TaskResult(taskId, 0, status, "Script action did not complete", index + 1)
            }
        }
        return TaskResult(taskId, 0, TaskStatus.COMPLETED, script.optional("answer").orEmpty(), actions.size)
    }

    override fun revise(correction: String) = error("scripted driver does not accept corrections")

    override fun cancel() {
        cancelled = true
    }
}
