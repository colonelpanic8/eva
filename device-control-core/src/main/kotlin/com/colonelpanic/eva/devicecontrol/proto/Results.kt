@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.colonelpanic.eva.devicecontrol.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("kind")
sealed class ActionDetails

@Serializable
@SerialName("set_text")
data class SetTextDetails(
    val element: Int,
    val verified: Boolean,
    val expected: String?,
    val actual: String?,
) : ActionDetails()

@Serializable
@SerialName("screenshot")
data class ScreenshotDetails(
    @SerialName("observation_id")
    val observationId: String,
    val width: Int,
    val height: Int,
    val png: String,
) : ActionDetails()

/** Backends can only press Enter, which runs the field's own editor action. */
@Serializable
@SerialName("ime_action")
data class ImeActionDetails(
    val requested: ImeActionName,
    @SerialName("performed_as")
    val performedAs: String = "enter",
) : ActionDetails()

@Serializable
data class ActionResult(
    @SerialName("action_id")
    val actionId: String,
    val kind: ActionKind,
    val ok: Boolean,
    @SerialName("started_at")
    val startedAt: String,
    @SerialName("finished_at")
    val finishedAt: String,
    /** Null only for OUTCOME_UNKNOWN results whose re-observation failed. */
    val observation: Observation?,
    val details: ActionDetails? = null,
    val error: ErrorInfo? = null,
    @SerialName("execution_status")
    val executionStatus: ExecutionStatus = ExecutionStatus.EXECUTED,
    @SerialName("post_observation_failure")
    val postObservationFailure: String? = null,
    /** The screen was still changing when the backend stopped waiting; observe again. */
    val unsettled: Boolean = false,
)
