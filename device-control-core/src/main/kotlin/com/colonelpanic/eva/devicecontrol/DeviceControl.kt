package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.devicecontrol.proto.Action
import com.colonelpanic.eva.devicecontrol.proto.ActionResult
import com.colonelpanic.eva.devicecontrol.proto.ErrorInfo
import com.colonelpanic.eva.devicecontrol.proto.Observation

/** Device I/O only. Mutations refer to the last observation, and are never automatically retried. */
interface DeviceBackend {
    suspend fun observe(): Observation

    suspend fun perform(action: Action): ActionResult
}

class ObservationFailure(
    val error: ErrorInfo,
) : Exception(error.javaClass.simpleName)

/** One task instance; run waits for its terminal result, while controls remain independent. */
interface TaskAgent {
    suspend fun run(
        goal: String,
        onProgress: (TaskProgress) -> Unit,
    ): TaskResult

    fun revise(correction: String)

    fun cancel()
}

data class TaskProgress(
    val taskId: String,
    val revision: Long,
    val step: Int,
    val phase: TaskPhase,
    val message: String? = null,
    val timing: StepTiming = StepTiming(),
)

enum class TaskPhase { OBSERVING, THINKING, ACTING, NEEDS_INPUT, PROGRESS }

data class StepTiming(
    val observationMillis: Long = 0,
    val modelMillis: Long = 0,
    val actionMillis: Long = 0,
)

data class TaskResult(
    val taskId: String,
    val revision: Long,
    val status: TaskStatus,
    val summary: String,
    val steps: Int,
)

enum class TaskStatus { COMPLETED, FAILED, CANCELLED, UNKNOWN }
