package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.audio.MediaControls
import com.colonelpanic.eva.audio.RealtimeMediaState

data class ConversationState(
    /** The thread on screen; null until one exists. */
    val threadId: String? = null,
    val entries: List<ConversationEntry> = emptyList(),
    /** A turn task is running on the shown thread, attached or not. */
    val working: Boolean = false,
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
)

data class ConversationEntry(
    val id: String,
    val request: String,
    val response: String,
    val status: EntryStatus,
    val destination: String? = null,
    val capabilityId: String? = null,
    val actionTitle: String? = null,
    /** The turn this action ran inside, so it can be shown under that request. */
    val parentId: String? = null,
)

/** One turn and the actions the model took inside it, in arrival order. */
data class EntryGroup(
    val entry: ConversationEntry,
    val actions: List<ConversationEntry> = emptyList(),
)

/**
 * Nests each action under the turn that produced it. An action whose turn is not listed,
 * such as restored history or a turn that aged out of the window, stands on its own.
 */
fun groups(entries: List<ConversationEntry>): List<EntryGroup> {
    val turns = entries.map { it.id }.toSet()
    val nested = entries.filter { it.parentId in turns }.groupBy { checkNotNull(it.parentId) }
    return entries
        .filter { it.parentId !in turns }
        .map { EntryGroup(it, nested[it.id].orEmpty()) }
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
