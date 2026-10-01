package com.colonelpanic.eva.conversation

data class TaskSnapshot(
    val threadId: String,
    val threadTitle: String,
    val taskId: String,
    val request: String,
    val kind: TaskKind,
    val state: TaskState,
    val startedAt: Long,
    val lastProgressAt: Long,
    val actionCount: Int,
    val lastActionTitle: String?,
    val lastActionStatus: String?,
    val holdsDeviceLease: Boolean,
    val coverage: WorkCoverage,
    val looksStuck: Boolean = false,
)

enum class TaskKind { VOICE_TURN, TYPED_TURN, DELEGATED_TEXT_AGENT, REHOMED_CONTINUATION, DEVICE_TASK }

enum class TaskState { CONNECTING, WORKING, WAITING_FOR_DEVICE, WAITING_FOR_EXTENSION, NEEDS_INPUT, RELEASING_DEVICE, STOPPING }

enum class WorkCoverage { NONE, LONG_RUNNING, SHORT_SERVICE }

internal data class TaskProgressRecord(
    val startedAt: Long,
    var lastProgressAt: Long = startedAt,
    var lastActionCallId: String? = null,
    var lastActionTitle: String? = null,
    var lastActionStatus: String? = null,
    var waiting: Boolean = false,
)

internal fun taskState(
    active: Boolean,
    releasing: Boolean,
    needsInput: Boolean,
    waitingForDevice: Boolean,
    deviceOwner: Boolean,
    waitingForExtension: Boolean,
    connecting: Boolean,
): TaskState =
    when {
        releasing -> TaskState.RELEASING_DEVICE
        !active -> TaskState.STOPPING
        needsInput -> TaskState.NEEDS_INPUT
        deviceOwner -> TaskState.WORKING
        waitingForDevice -> TaskState.WAITING_FOR_DEVICE
        waitingForExtension -> TaskState.WAITING_FOR_EXTENSION
        connecting -> TaskState.CONNECTING
        else -> TaskState.WORKING
    }

internal fun TaskState.canStall() = this == TaskState.WORKING || this == TaskState.CONNECTING
