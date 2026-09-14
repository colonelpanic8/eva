package com.colonelpanic.eva.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.conversation.ConversationState
import com.colonelpanic.eva.conversation.ProviderStatus

/** What the connect area offers, derived only from state the screen already has. */
internal data class ConnectPrompt(
    val canConnect: Boolean,
    val supporting: String?,
)

/**
 * Credentials live in settings now, so the bar's job is to say whether a session can
 * start and, when it cannot, where to go. [usable] mirrors the old panel's guard: a
 * conversation that is still loading or has lost its history cannot open a session.
 */
internal fun connectPrompt(
    state: ConversationState,
    hasCredential: Boolean,
): ConnectPrompt {
    val usable = !state.isLoading && state.errorMessage == null
    return when {
        !usable -> ConnectPrompt(canConnect = false, supporting = null)
        !hasCredential -> ConnectPrompt(canConnect = false, supporting = "Add an account, API key, or paired host in Settings.")
        else -> ConnectPrompt(canConnect = true, supporting = null)
    }
}

/**
 * The connection affordance, docked above the composer. Disconnected it offers the two
 * ways in; otherwise it reports the live session and offers the way out.
 */
@Composable
internal fun ConnectBar(
    state: ConversationState,
    hasCredential: Boolean,
    onConnect: () -> Unit,
    onVoice: () -> Unit,
    onDisconnect: () -> Unit,
    denial: MicrophoneDenial?,
    onRetryMicrophone: () -> Unit,
    onDismissDenial: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            denial != null -> {
                MicrophoneDenied(denial, onRetryMicrophone, onDismissDenial)
            }

            state.providerStatus == ProviderStatus.DISCONNECTED -> {
                val prompt = connectPrompt(state, hasCredential)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onVoice, enabled = prompt.canConnect) { Text("Start voice") }
                    OutlinedButton(onClick = onConnect, enabled = prompt.canConnect) { Text("Connect for text") }
                }
                prompt.supporting?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            else -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onDisconnect) {
                        Text(if (state.providerStatus == ProviderStatus.CONNECTING) "Connecting… Cancel" else "Disconnect")
                    }
                    state.providerModel?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** A turn task is running on the shown thread. Hanging up does not end it; this does. */
@Composable
internal fun WorkingRow(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "EVA is working on the last request",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onStop) { Text("Stop") }
    }
}

/**
 * Provider trouble outlives the connect area: a media failure or an interrupted tool
 * exchange arrives mid-session, when the connect bar is not on screen at all.
 */
@Composable
internal fun ProviderMessage(
    message: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun MicrophoneDenied(
    denial: MicrophoneDenial,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Text(
        text = microphoneDenialMessage(denial),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRetry) { Text(if (denial.canAskAgain) "Allow microphone" else "Open settings") }
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

internal fun microphoneDenialMessage(denial: MicrophoneDenial): String =
    if (denial.canAskAgain) {
        "Microphone access was declined. EVA cannot hold a voice conversation without it."
    } else {
        "Microphone access is blocked for EVA. Allow it in system settings to use voice."
    }
