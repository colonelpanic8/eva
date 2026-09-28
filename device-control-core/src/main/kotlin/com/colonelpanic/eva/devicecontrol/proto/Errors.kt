@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.colonelpanic.eva.devicecontrol.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("code")
sealed class ErrorInfo {
    abstract val actionId: String?
    abstract val observationId: String?

    /** Only NOT_DISPATCHED guarantees the action had no effect. */
    abstract val executionStatus: ExecutionStatus
}

@Serializable
@SerialName("stale_observation")
data class StaleObservation(
    @SerialName("action_id")
    override val actionId: String? = null,
    @SerialName("observation_id")
    override val observationId: String? = null,
    @SerialName("latest_observation_id")
    val latestObservationId: String? = null,
    @SerialName("execution_status")
    override val executionStatus: ExecutionStatus = ExecutionStatus.NOT_DISPATCHED,
) : ErrorInfo()

@Serializable
@SerialName("element_not_found")
data class ElementNotFound(
    @SerialName("action_id")
    override val actionId: String? = null,
    @SerialName("observation_id")
    override val observationId: String? = null,
    val element: Int,
    @SerialName("execution_status")
    override val executionStatus: ExecutionStatus = ExecutionStatus.NOT_DISPATCHED,
) : ErrorInfo()

@Serializable
@SerialName("not_actionable")
data class NotActionable(
    @SerialName("action_id")
    override val actionId: String? = null,
    @SerialName("observation_id")
    override val observationId: String? = null,
    val element: Int? = null,
    val reason: NotActionableReason,
    @SerialName("execution_status")
    override val executionStatus: ExecutionStatus = ExecutionStatus.NOT_DISPATCHED,
) : ErrorInfo()

@Serializable
@SerialName("text_mismatch")
data class TextMismatch(
    @SerialName("action_id")
    override val actionId: String? = null,
    @SerialName("observation_id")
    override val observationId: String? = null,
    val element: Int,
    val expected: String?,
    val actual: String?,
    @SerialName("execution_status")
    override val executionStatus: ExecutionStatus = ExecutionStatus.EXECUTED,
) : ErrorInfo()

@Serializable
@SerialName("protected_content")
data class ProtectedContent(
    @SerialName("action_id")
    override val actionId: String? = null,
    @SerialName("observation_id")
    override val observationId: String? = null,
    val reason: ProtectedReason,
    @SerialName("execution_status")
    override val executionStatus: ExecutionStatus = ExecutionStatus.EXECUTED,
) : ErrorInfo()

@Serializable
@SerialName("app_not_found")
data class AppNotFound(
    @SerialName("action_id")
    override val actionId: String? = null,
    @SerialName("observation_id")
    override val observationId: String? = null,
    @SerialName("package")
    val packageName: String,
    @SerialName("execution_status")
    override val executionStatus: ExecutionStatus = ExecutionStatus.NOT_DISPATCHED,
) : ErrorInfo()

@Serializable
@SerialName("backend_unavailable")
data class BackendUnavailable(
    @SerialName("action_id")
    override val actionId: String? = null,
    @SerialName("observation_id")
    override val observationId: String? = null,
    val backend: String,
    @SerialName("execution_status")
    override val executionStatus: ExecutionStatus = ExecutionStatus.OUTCOME_UNKNOWN,
) : ErrorInfo()

@Serializable
@SerialName("timeout")
data class Timeout(
    @SerialName("action_id")
    override val actionId: String? = null,
    @SerialName("observation_id")
    override val observationId: String? = null,
    val operation: Operation,
    @SerialName("timeout_s")
    val timeoutS: Double,
    @SerialName("execution_status")
    override val executionStatus: ExecutionStatus = ExecutionStatus.OUTCOME_UNKNOWN,
) : ErrorInfo()

@Serializable
@SerialName("unsupported")
data class Unsupported(
    @SerialName("action_id")
    override val actionId: String? = null,
    @SerialName("observation_id")
    override val observationId: String? = null,
    val kind: ActionKind,
    val backend: String,
    @SerialName("execution_status")
    override val executionStatus: ExecutionStatus = ExecutionStatus.NOT_DISPATCHED,
) : ErrorInfo()

@Serializable
@SerialName("revision_rejected")
data class RevisionRejected(
    @SerialName("action_id")
    override val actionId: String? = null,
    @SerialName("observation_id")
    override val observationId: String? = null,
    @SerialName("task_id")
    val taskId: String,
    @SerialName("action_revision")
    val actionRevision: Long,
    @SerialName("current_revision")
    val currentRevision: Long,
    @SerialName("current_task_id")
    val currentTaskId: String? = null,
    @SerialName("execution_status")
    override val executionStatus: ExecutionStatus = ExecutionStatus.NOT_DISPATCHED,
) : ErrorInfo()

@Serializable
@SerialName("duplicate_action")
data class DuplicateAction(
    @SerialName("action_id")
    override val actionId: String? = null,
    @SerialName("observation_id")
    override val observationId: String? = null,
    @SerialName("execution_status")
    override val executionStatus: ExecutionStatus = ExecutionStatus.NOT_DISPATCHED,
) : ErrorInfo()

@Serializable
@SerialName("stopped")
data class Stopped(
    @SerialName("action_id")
    override val actionId: String? = null,
    @SerialName("observation_id")
    override val observationId: String? = null,
    @SerialName("execution_status")
    override val executionStatus: ExecutionStatus = ExecutionStatus.NOT_DISPATCHED,
) : ErrorInfo()

@Serializable
@SerialName("lease_expired")
data class LeaseExpired(
    @SerialName("action_id")
    override val actionId: String? = null,
    @SerialName("observation_id")
    override val observationId: String? = null,
    @SerialName("lease_id")
    val leaseId: String? = null,
    @SerialName("execution_status")
    override val executionStatus: ExecutionStatus = ExecutionStatus.NOT_DISPATCHED,
) : ErrorInfo()

@Serializable
@SerialName("lease_invalid")
data class LeaseInvalid(
    @SerialName("action_id")
    override val actionId: String? = null,
    @SerialName("observation_id")
    override val observationId: String? = null,
    @SerialName("lease_id")
    val leaseId: String? = null,
    @SerialName("execution_status")
    override val executionStatus: ExecutionStatus = ExecutionStatus.NOT_DISPATCHED,
) : ErrorInfo()

@Serializable
@SerialName("not_long_clickable")
data class NotLongClickable(
    @SerialName("action_id")
    override val actionId: String? = null,
    @SerialName("observation_id")
    override val observationId: String? = null,
    val element: Int,
    @SerialName("execution_status")
    override val executionStatus: ExecutionStatus = ExecutionStatus.NOT_DISPATCHED,
) : ErrorInfo()

@Serializable
@SerialName("gate_rejected")
data class GateRejected(
    @SerialName("action_id")
    override val actionId: String? = null,
    @SerialName("observation_id")
    override val observationId: String? = null,
    val reason: GateRejectedReason,
    @SerialName("execution_status")
    override val executionStatus: ExecutionStatus = ExecutionStatus.NOT_DISPATCHED,
) : ErrorInfo()

/** Raised by the controller, never by a device. `outcomeError` is an error code. */
@Serializable
@SerialName("late_result")
data class LateResult(
    @SerialName("action_id")
    override val actionId: String? = null,
    @SerialName("observation_id")
    override val observationId: String? = null,
    @SerialName("task_id")
    val taskId: String,
    @SerialName("action_revision")
    val actionRevision: Long,
    @SerialName("current_revision")
    val currentRevision: Long? = null,
    @SerialName("outcome_error")
    val outcomeError: String? = null,
    @SerialName("execution_status")
    override val executionStatus: ExecutionStatus = ExecutionStatus.EXECUTED,
) : ErrorInfo()

@Serializable
enum class NotActionableReason {
    @SerialName("not_clickable")
    NOT_CLICKABLE,

    @SerialName("not_editable")
    NOT_EDITABLE,

    @SerialName("not_scrollable")
    NOT_SCROLLABLE,

    @SerialName("disabled")
    DISABLED,

    @SerialName("out_of_bounds")
    OUT_OF_BOUNDS,

    @SerialName("no_scrollable")
    NO_SCROLLABLE,

    @SerialName("root_scope")
    ROOT_SCOPE,

    @SerialName("not_focused")
    NOT_FOCUSED,
}

@Serializable
enum class ProtectedReason {
    @SerialName("secure_window")
    SECURE_WINDOW,

    @SerialName("screenshot_rejected")
    SCREENSHOT_REJECTED,

    @SerialName("content_unavailable")
    CONTENT_UNAVAILABLE,
}

@Serializable
enum class Operation {
    @SerialName("observe")
    OBSERVE,

    @SerialName("perform")
    PERFORM,
}

@Serializable
enum class ExecutionStatus {
    @SerialName("not_dispatched")
    NOT_DISPATCHED,

    @SerialName("in_flight")
    IN_FLIGHT,

    @SerialName("executed")
    EXECUTED,

    @SerialName("outcome_unknown")
    OUTCOME_UNKNOWN,
}

@Serializable
enum class GateRejectedReason {
    @SerialName("stopped")
    STOPPED,

    @SerialName("expired")
    EXPIRED,

    @SerialName("wrong_generation")
    WRONG_GENERATION,

    @SerialName("busy")
    BUSY,

    @SerialName("duplicate_conflict")
    DUPLICATE_CONFLICT,
}
