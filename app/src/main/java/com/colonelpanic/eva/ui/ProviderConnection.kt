package com.colonelpanic.eva.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.conversation.ConversationState
import com.colonelpanic.eva.conversation.ProviderStatus
import com.colonelpanic.eva.providers.openai.SignInState

@Composable
internal fun ProviderConnection(
    state: ConversationState,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onVoice: (String, Boolean) -> Unit,
    hasApiKey: Boolean = false,
    onSaveApiKey: (String) -> Unit = {},
    onClearApiKey: () -> Unit = {},
    account: String? = null,
    signIn: SignInState = SignInState.Idle,
    onSignIn: () -> Unit = {},
    onCancelSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
    textModel: String = "",
    realtimeModel: String = "",
    availableTextModels: List<String> = emptyList(),
    availableRealtimeModels: List<String> = emptyList(),
    onSelectTextModel: (String) -> Unit = {},
    onSelectRealtimeModel: (String) -> Unit = {},
    denial: MicrophoneDenial? = null,
    onRetryMicrophone: () -> Unit = {},
    onListenOnlyInstead: () -> Unit = {},
    onDismissDenial: () -> Unit = {},
) {
    var listenOnly by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    val usable = !state.isLoading && state.errorMessage == null
    val ready = (account != null || hasApiKey || link.isNotBlank()) && usable
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.providerStatus == ProviderStatus.DISCONNECTED) {
            if (denial != null) {
                MicrophoneDenied(denial, onRetryMicrophone, onListenOnlyInstead, onDismissDenial)
            } else {
                if (account != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(account, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onSignOut) { Text("Sign out") }
                    }
                } else {
                    ChatGptSignIn(signIn, onSignIn, onCancelSignIn)
                }
                ApiKeyField(
                    hasApiKey = hasApiKey,
                    expanded = showApiKey || hasApiKey,
                    onExpand = { showApiKey = true },
                    onSaveApiKey = onSaveApiKey,
                    onClearApiKey = onClearApiKey,
                )
                if (account != null || hasApiKey) {
                    ModelPicker("Text model", textModel, availableTextModels, onSelectTextModel)
                    ModelPicker("Voice model", realtimeModel, availableRealtimeModels, onSelectRealtimeModel)
                }
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text(if (hasApiKey) "Paired host link (optional)" else "Paired host link") },
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
            state.providerModel?.let {
                Text(
                    if (state.voiceMode) "Voice model: $it" else "Model: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.providerMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * Shows the one-time code and where to approve it. Nothing is typed on the phone, which is
 * what lets a subscription sign-in work here at all.
 */
@Composable
private fun ChatGptSignIn(
    signIn: SignInState,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    when (signIn) {
        is SignInState.Requesting -> {
            Text("Asking OpenAI for a sign-in code…", style = MaterialTheme.typography.bodySmall)
        }

        is SignInState.Waiting -> {
            val context = LocalContext.current
            // A fresh code deserves a fresh button, so the label cannot claim a stale copy.
            var copied by remember(signIn.userCode) { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Open the sign-in page and enter this code:", style = MaterialTheme.typography.bodySmall)
                Text(signIn.userCode, style = MaterialTheme.typography.headlineSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { uriHandler.openUri(signIn.verificationUrl) }) { Text("Open sign-in page") }
                    OutlinedButton(
                        onClick = { copied = copyToClipboard(context, signIn.userCode) },
                    ) { Text(if (copied) "Copied" else "Copy code") }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                Text(
                    "${signIn.verificationUrl} · the code expires in 15 minutes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is SignInState.Failed -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(signIn.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Button(onClick = onSignIn) { Text("Sign in with ChatGPT") }
            }
        }

        is SignInState.Idle -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onSignIn) { Text("Sign in with ChatGPT") }
                Text(
                    "Typed chat is then covered by your ChatGPT subscription.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Approving the code usually happens on another device, where the phone's clipboard cannot
 * help, but copying still saves retyping when the browser is this phone's own.
 */
private fun copyToClipboard(
    context: Context,
    code: String,
): Boolean {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText("EVA sign-in code", code))
    return true
}

/** The metered alternative, kept out of the way until it is asked for. */
@Composable
private fun ApiKeyField(
    hasApiKey: Boolean,
    expanded: Boolean,
    onExpand: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    when {
        hasApiKey -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("OpenAI API key saved on this phone", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onClearApiKey) { Text("Remove") }
            }
        }

        !expanded -> {
            TextButton(onClick = onExpand) { Text("Use an API key instead") }
        }

        else -> {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("OpenAI API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = {
                    onSaveApiKey(apiKey)
                    apiKey = ""
                },
                enabled = apiKey.isNotBlank(),
            ) { Text("Save key on this phone") }
        }
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
