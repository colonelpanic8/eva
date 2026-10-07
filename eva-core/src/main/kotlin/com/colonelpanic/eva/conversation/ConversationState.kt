package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.audio.MediaControls
import com.colonelpanic.eva.audio.RealtimeMediaState
import com.colonelpanic.eva.capability.ActionInitiator

data class ConversationState(
    /** The thread on screen; null until one exists. */
    val threadId: String? = null,
    /** The live attachment's thread, which can differ while browsing during a voice call. */
    val attachedThreadId: String? = null,
    val entries: List<ConversationEntry> = emptyList(),
    /** A turn task is running on the shown thread, attached or not. */
    val working: Boolean = false,
    /** The shown thread has a request on the live attachment; typed input steers it. */
    val foregroundWorking: Boolean = false,
    val currentQuestion: QuestionEvidence? = null,
    val pendingQuestionCount: Int = 0,
    val voiceQuestion: String? = null,
    /** Typed input not yet taken by the running request, sent once it can be, oldest first. */
    val waitingInputs: List<String> = emptyList(),
    val deviceTaskActive: Boolean = false,
    /** The shown thread's running device task phase or step note; cleared when the task ends. */
    val deviceTaskProgress: String? = null,
    val isLoading: Boolean = true,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val providerStatus: ProviderStatus = ProviderStatus.DISCONNECTED,
    val providerMessage: String? = null,
    val providerModel: String? = null,
    val voiceMode: Boolean = false,
    val mediaState: RealtimeMediaState = RealtimeMediaState.Idle,
    val mediaControls: MediaControls = MediaControls(),
    val providerLabel: String = "Not connected",
) {
    val voiceOnAnotherThread: Boolean get() = voiceMode && attachedThreadId != null && attachedThreadId != threadId

    val acceptsTextInput: Boolean get() =
        !isLoading && errorMessage == null && !voiceOnAnotherThread &&
            (
                currentQuestion != null || deviceTaskActive ||
                    (providerStatus == ProviderStatus.CONNECTED && !voiceMode)
            )
}

data class ConversationEntry(
    val id: String,
    val request: String,
    val response: String,
    val status: EntryStatus,
    val destination: String? = null,
    val capabilityId: String? = null,
    val actionTitle: String? = null,
    val arguments: Map<String, String> = emptyMap(),
    /** An action's own result, without the provenance header [response] carries. */
    val result: String? = null,
    /** The turn or text leg this ran inside, so it can be shown under it. */
    val parentId: String? = null,
    /** Set when this entry is a text leg rather than an action. */
    val textLeg: TextLegDetails? = null,
    val question: QuestionEvidence? = null,
    val initiator: ActionInitiator? = null,
    /** Each screen read and input a device task made, live while it runs. */
    val deviceSteps: List<DeviceStep> = emptyList(),
)

data class DeviceStep(
    val step: Int,
    val kind: String,
    val detail: String,
    /** `ok`, `not_dispatched`, `asked`, a finish status, or the backend's error name. */
    val result: String,
    val intent: String? = null,
    val backend: String? = null,
)

data class TextLegDetails(
    /** What voice delegated; null when the turn moved to text because its connection ended. */
    val task: String?,
    val instructions: String,
    val historyItems: Int,
    val status: TurnStatus,
)

/** One turn and what ran inside it, in arrival order; a text leg holds its own actions. */
data class EntryGroup(
    val entry: ConversationEntry,
    val children: List<EntryGroup> = emptyList(),
) {
    val actions: List<ConversationEntry> get() = children.map { it.entry }
}

/**
 * Nests each action under the turn or text leg that produced it. An entry whose parent is not
 * listed, such as restored history or a turn that aged out of the window, stands on its own.
 */
fun groups(entries: List<ConversationEntry>): List<EntryGroup> {
    val ids = entries.map { it.id }.toSet()
    val nested = entries.filter { it.parentId in ids }.groupBy { checkNotNull(it.parentId) }

    fun group(entry: ConversationEntry): EntryGroup = EntryGroup(entry, nested[entry.id].orEmpty().map(::group))
    return entries.filter { it.parentId !in ids }.map(::group)
}

enum class ProviderStatus { DISCONNECTED, CONNECTING, CONNECTED }

enum class EntryStatus {
    PENDING,
    DISPATCHING,
    HANDED_OFF,
    COMPLETED,
    NOT_EXECUTED,
    FAILED,
    UNKNOWN,
    ANSWER,
    SESSION,
}

/** One row of the thread list. */
data class ThreadSummary(
    val id: String,
    val title: String,
    val updatedAtMillis: Long,
    val working: Boolean,
)
