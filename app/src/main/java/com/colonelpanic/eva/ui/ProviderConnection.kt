package com.colonelpanic.eva.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.conversation.ConversationState
import com.colonelpanic.eva.conversation.ProviderStatus

@Composable
internal fun ProviderConnection(
    state: ConversationState,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onVoice: (String, Boolean) -> Unit,
    denial: MicrophoneDenial? = null,
    onRetryMicrophone: () -> Unit = {},
    onListenOnlyInstead: () -> Unit = {},
    onDismissDenial: () -> Unit = {},
) {
    var listenOnly by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    val ready = link.isNotBlank() && !state.isLoading && state.errorMessage == null
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.providerStatus == ProviderStatus.DISCONNECTED) {
            if (denial != null) {
                MicrophoneDenied(denial, onRetryMicrophone, onListenOnlyInstead, onDismissDenial)
            } else {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("Broker connection link") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                    Button(onClick = {
                        onConnect(link)
                        link = ""
                    }, enabled = ready) {
                        Text("Connect for text")
                    }
                    Button(
                        onClick = {
                            onVoice(link, listenOnly)
                            link = ""
                        },
                        enabled = ready,
                    ) { Text(if (listenOnly) "Start listen-only" else "Start voice") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = listenOnly,
                        onCheckedChange = { listenOnly = it },
                        modifier = Modifier.semantics { contentDescription = "Listen only, no microphone" },
                    )
                    Text("Listen only: EVA speaks, your microphone stays off", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            Button(onClick = onDisconnect) {
                Text(if (state.providerStatus == ProviderStatus.CONNECTING) "Connecting… Cancel" else "Connected · Disconnect")
            }
        }
        state.providerMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun MicrophoneDenied(
    denial: MicrophoneDenial,
    onRetry: () -> Unit,
    onListenOnly: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = microphoneDenialMessage(denial),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry) { Text(if (denial.canAskAgain) "Allow microphone" else "Open settings") }
            OutlinedButton(onClick = onListenOnly) { Text("Listen only instead") }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

internal fun microphoneDenialMessage(denial: MicrophoneDenial): String =
    if (denial.canAskAgain) {
        "Microphone access was declined. Voice needs it to hear you; listen-only works without it."
    } else {
        "Microphone access is blocked for EVA. Allow it in system settings, or continue listen-only."
    }
