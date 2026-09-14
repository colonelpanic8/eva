package com.colonelpanic.eva.assist

import com.colonelpanic.eva.conversation.ProviderStatus

/** What a freshly shown assistant session should do with the conversation it found. */
internal enum class AssistantStart {
    /** Nothing is running: open a voice session for what the user is about to say. */
    CONNECT,

    /** A session is already live, most likely the one this panel was opened to control. */
    JOIN,

    /** No session can start, and a session has no activity from which to ask for the grant. */
    NEEDS_MICROPHONE,
}

internal fun assistantStart(
    microphoneGranted: Boolean,
    voiceMode: Boolean,
    providerStatus: ProviderStatus,
): AssistantStart =
    when {
        !microphoneGranted -> AssistantStart.NEEDS_MICROPHONE
        voiceMode && providerStatus != ProviderStatus.DISCONNECTED -> AssistantStart.JOIN
        else -> AssistantStart.CONNECT
    }
