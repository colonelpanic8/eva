package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.audio.MediaControls
import com.colonelpanic.eva.audio.RealtimeMediaState

data class ConversationState(
    val entries: List<ConversationEntry> = emptyList(),
    val isLoading: Boolean = true,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val providerStatus: ProviderStatus = ProviderStatus.DISCONNECTED,
    val providerMessage: String? = null,
    val providerModel: String? = null,
    val voiceMode: Boolean = false,
    val mediaState: RealtimeMediaState = RealtimeMediaState.Idle,
    val mediaControls: MediaControls = MediaControls(),
    val providerLabel: String = "ChatGPT through paired host",
)

data class ConversationEntry(
    val id: String,
    val request: String,
    val response: String,
    val status: EntryStatus,
    val destination: String? = null,
    val capabilityId: String? = null,
    val actionTitle: String? = null,
)

enum class ProviderStatus { DISCONNECTED, CONNECTING, CONNECTED }

enum class EntryStatus {
    PENDING,
    DISPATCHING,
    HANDED_OFF,
    NOT_EXECUTED,
    FAILED,
    UNKNOWN,
    ANSWER,
    SESSION,
}
