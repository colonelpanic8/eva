package com.colonelpanic.eva.desktop

import com.colonelpanic.eva.conversation.ConversationState
import com.colonelpanic.eva.conversation.ProviderStatus
import com.colonelpanic.eva.conversation.ThreadController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

/** Creates a thread and waits until the controller shows it. */
internal suspend fun startThread(
    controller: ThreadController,
    ui: CoroutineContext,
) {
    val previous = controller.state.value.threadId
    withContext(ui) { controller.newThread() }
    controller.state.first { it.threadId != null && it.threadId != previous }
}

/** The provider label once connected, or null after reporting why it could not connect. */
internal suspend fun connect(
    controller: ThreadController,
    ui: CoroutineContext,
): String? {
    withContext(ui) { controller.connect("") }
    val connected =
        withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
            controller.state.first { it.providerStatus == ProviderStatus.CONNECTED || it.errorMessage != null }
        }
    if (connected?.providerStatus == ProviderStatus.CONNECTED) return connected.providerLabel
    System.err.println(connectionProblem(connected))
    return null
}

/** Starts a new thread on a new connection, since an attachment stays on the thread it opened with. */
internal suspend fun reconnectToNewThread(
    controller: ThreadController,
    ui: CoroutineContext,
): String? {
    withContext(ui) { controller.disconnect() }
    startThread(controller, ui)
    return connect(controller, ui)
}

internal fun connectionProblem(state: ConversationState?): String =
    state?.errorMessage ?: state?.providerMessage ?: "Could not connect to the model."

private const val CONNECT_TIMEOUT_MILLIS = 30_000L
