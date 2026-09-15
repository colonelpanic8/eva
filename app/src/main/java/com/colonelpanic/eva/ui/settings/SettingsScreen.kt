package com.colonelpanic.eva.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.providers.openai.OpenAiModels
import com.colonelpanic.eva.providers.openai.SignInState
import com.colonelpanic.eva.providers.spotify.SpotifyConnectState
import com.colonelpanic.eva.ui.MenuButton
import com.colonelpanic.eva.ui.ModelPicker
import com.colonelpanic.eva.ui.ReasoningEffortPicker
import com.colonelpanic.eva.ui.evaTopAppBarColors
import com.colonelpanic.eva.ui.theme.EvaTheme

/**
 * Everything that outlives a session and is not about one capability: credentials, models,
 * appearance, and the device grants EVA holds. It is reachable while connected, which the
 * old in-header panel was not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onOpenDrawer: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { MenuButton(onOpenDrawer) },
                colors = evaTopAppBarColors(),
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState()),
        ) {
            ConfigurationSection(state, actions)
            SettingsDivider()
            AccountSection(state, actions)
            SettingsDivider()
            ModelsSection(state, actions)
            SettingsDivider()
            AssistantSection(state, actions)
            SettingsDivider()
            MediaSection(state, actions)
            ScreenControlSection(state, actions)
            SettingsDivider()
            SpotifySection(state, actions)
            SettingsDivider()
            AppearanceSection(state, actions)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConfigurationSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val git = state.configuration.git
    var remote by remember(git.remoteUrl) { mutableStateOf(git.remoteUrl) }
    var branch by remember(git.branch) { mutableStateOf(git.branch) }
    var authorName by remember(git.authorName) { mutableStateOf(git.authorName) }
    var authorEmail by remember(git.authorEmail) { mutableStateOf(git.authorEmail) }
    var username by remember(git.username) { mutableStateOf(git.username) }
    var token by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }
    SettingsSection("User configuration") {
        SettingsBlock {
            Text(
                state.configuration.linkedFolder?.let {
                    "Linked to $it/${com.colonelpanic.eva.data.configuration.EvaConfigurationCodec.FILE_NAME}"
                }
                    ?: "Choose a synced folder, or let EVA manage a real Git checkout.",
            )
            state.configuration.message?.let {
                Text(
                    it,
                    color =
                        if (state.configuration.isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            if (state.configuration.gitEnabled) {
                Text(
                    "Git status: ${state.configuration.gitCondition.name.lowercase().replace('_', ' ')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.configuration.setupRequired.forEach { Text("Setup required: $it", color = MaterialTheme.colorScheme.error) }
            SettingsRow(
                title = "Managed Git sync",
                supporting = "HTTPS only; the token stays encrypted on this device.",
            ) {
                Switch(checked = state.configuration.gitEnabled, onCheckedChange = actions.onGitEnabled)
            }
            OutlinedTextField(
                value = remote,
                onValueChange = { remote = it },
                label = { Text("HTTPS remote URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = branch,
                onValueChange = { branch = it },
                label = { Text("Branch") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = authorName,
                onValueChange = { authorName = it },
                label = { Text("Commit author name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = authorEmail,
                onValueChange = { authorEmail = it },
                label = { Text("Commit author email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Git username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(if (git.tokenPresent) "New Git token (optional)" else "Git token") },
                supportingText = { Text(if (git.tokenPresent) "A token is saved on this device." else "Needed to push an HTTPS remote.") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            inputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !state.configuration.busy,
                    onClick = {
                        inputError = actions.onSaveGit(remote, branch, authorName, authorEmail, username, token)
                        if (inputError == null) token = ""
                    },
                ) { Text("Save & connect") }
                if (state.configuration.gitEnabled && state.configuration.linkedFolder != null) {
                    OutlinedButton(enabled = !state.configuration.busy, onClick = actions.onReloadConfiguration) { Text("Sync") }
                }
                if (git.tokenPresent) {
                    TextButton(onClick = actions.onClearGitToken) { Text("Remove token") }
                }
            }
            if (!state.configuration.gitEnabled) {
                Text("Folder mode keeps Git operations in another app.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = actions.onSelectConfigurationFolder) {
                        Text(if (state.configuration.linkedFolder == null) "Choose folder" else "Change folder")
                    }
                    if (state.configuration.linkedFolder != null) {
                        TextButton(onClick = actions.onReloadConfiguration) { Text("Reload") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    SettingsSection("Account") {
        if (state.account != null) {
            SettingsRow(title = state.account, supporting = "Signed in with ChatGPT") {
                TextButton(onClick = actions.onSignOut) { Text("Sign out") }
            }
        } else {
            SettingsBlock { ChatGptSignIn(state.signIn, actions.onSignIn, actions.onCancelSignIn) }
        }
        SecretField(
            label = "OpenAI API key",
            savedTitle = "OpenAI API key",
            savedSupporting = "Saved on this phone",
            helper = "The metered alternative to a ChatGPT subscription.",
            saved = state.hasApiKey,
            saveLabel = "Save key on this phone",
            onSave = actions.onSaveApiKey,
            onClear = actions.onClearApiKey,
        )
        SecretField(
            label = "Paired host link",
            savedTitle = "Paired host",
            savedSupporting = "Link saved on this phone",
            helper = "Routes conversations through a paired host instead of OpenAI directly.",
            saved = state.hasHostLink,
            saveLabel = "Save link on this phone",
            onSave = actions.onSaveHostLink,
            onClear = actions.onClearHostLink,
        )
    }
}

@Composable
private fun ModelsSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    SettingsSection("Models") {
        SettingsBlock {
            ModelPicker("Text model", state.textModel, state.availableTextModels, OpenAiModels.TEXT, actions.onSelectTextModel)
            ModelPicker(
                "Voice model",
                state.realtimeModel,
                state.availableRealtimeModels,
                OpenAiModels.REALTIME,
                actions.onSelectRealtimeModel,
            )
            ReasoningEffortPicker(
                "Text reasoning effort",
                state.reasoningEffort,
                OpenAiModels.TEXT_REASONING_EFFORTS,
                actions.onSelectReasoningEffort,
            )
            ReasoningEffortPicker(
                "Voice reasoning effort",
                state.voiceReasoningEffort,
                OpenAiModels.VOICE_REASONING_EFFORTS,
                actions.onSelectVoiceReasoningEffort,
            )
            Text(
                text = "A change applies to the next connection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Android has no role request for the assistant, so the most EVA can do is say whether it
 * holds the role and open the screen where the user can grant it.
 */
@Composable
private fun AssistantSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    SettingsSection("Device assistant") {
        SettingsRow(
            title = if (state.isDeviceAssistant) "EVA answers the assist gesture" else "EVA is not the device assistant",
            supporting =
                if (state.isDeviceAssistant) {
                    "Holding the home button or power button opens EVA over whatever app is in front."
                } else {
                    "Pick EVA as the digital assistant app to reach it without opening it first."
                },
        ) {
            TextButton(onClick = actions.onOpenAssistantSettings) { Text("Change") }
        }
        SettingsSwitchRow(
            title = "Use one-and-done conversations for external launches",
            supporting =
                if (state.oneShotExternal) {
                    "After finishing the request, EVA says a short closing line and ends the call."
                } else {
                    "EVA stays on the line for follow-up requests until you say you are done."
                },
            checked = state.oneShotExternal,
            onCheckedChange = actions.onOneShotExternalChange,
        )
    }
}

/**
 * Notification access is the only grant an unprivileged app can hold that reveals other apps'
 * media sessions, and it is a broad one, so the row says plainly what turning it on gives away
 * and what still works without it.
 */
@Composable
private fun MediaSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    SettingsSection("Media controls") {
        SettingsRow(
            title =
                if (state.canSeeMediaSessions) {
                    "EVA can see and control what is playing"
                } else {
                    "EVA can press play and skip, but cannot see what is playing"
                },
            supporting =
                if (state.canSeeMediaSessions) {
                    "Pausing, skipping, and now-playing work for any app with lock-screen controls, and EVA " +
                        "reports what actually happened instead of only that a button was sent."
                } else {
                    "Turning on notification access lets EVA read what is playing, choose between apps, and confirm " +
                        "a pause worked. Android offers nothing narrower, so it also delivers your notifications to " +
                        "EVA. Reading message notifications additionally requires the opt-in on the Messaging screen."
                },
        ) {
            TextButton(onClick = actions.onOpenMediaControlSettings) { Text("Change") }
        }
    }
}

/**
 * The one capability that can reach into any other app, so it gets a switch rather than only the
 * Shizuku authorization that enables it. Switching it off removes the tools from the catalog EVA
 * sends, so the model is not told the ability exists.
 */
@Composable
private fun ScreenControlSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    if (!state.canControlScreen) return
    SettingsSection("Screen control") {
        SettingsSwitchRow(
            title = "Let EVA read and tap the screen",
            supporting =
                "Experimental, and only while Shizuku is running and has authorized EVA. It can read the " +
                    "elements of whatever app is in front, tap one, or replace one text field, which means it can " +
                    "send or buy things. Off removes these tools from what the model is offered.",
            checked = state.screenControlEnabled,
            onCheckedChange = actions.onScreenControlChange,
        )
    }
}

@Composable
private fun SpotifySection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val uriHandler = LocalUriHandler.current
    var clientId by remember(state.spotifyClientId) { mutableStateOf(state.spotifyClientId.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    SettingsSection("Spotify") {
        SettingsBlock {
            Text(
                "Queueing songs goes through Spotify's own API, which needs a Client ID from a free Spotify " +
                    "developer app. Create one at developer.spotify.com/dashboard, add the redirect URI " +
                    "eva://spotify, and paste the Client ID here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = clientId,
                onValueChange = {
                    clientId = it
                    error = null
                },
                label = { Text("Spotify Client ID") },
                supportingText = error?.let { message -> { Text(message) } },
                isError = error != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { error = actions.onSaveSpotifyClientId(clientId) },
                enabled = clientId.trim() != state.spotifyClientId.orEmpty(),
            ) { Text("Save") }
        }
        if (state.spotifyAccount != null) {
            SettingsRow(title = state.spotifyAccount, supporting = "Connected") {
                TextButton(onClick = actions.onDisconnectSpotify) { Text("Disconnect") }
            }
            if (state.spotifyPremium == false) {
                SettingsBlock {
                    Text(
                        "Spotify only lets Premium accounts queue songs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (state.spotifyClientId != null) {
            SettingsBlock {
                when (val connect = state.spotifyConnect) {
                    is SpotifyConnectState.Idle -> {
                        Button(onClick = { actions.onConnectSpotify()?.let(uriHandler::openUri) }) {
                            Text("Connect Spotify")
                        }
                    }

                    is SpotifyConnectState.Waiting -> {
                        Text("Finish signing in with Spotify in your browser.")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { uriHandler.openUri(connect.authorizationUrl) }) { Text("Open sign-in page") }
                            TextButton(onClick = actions.onCancelSpotifyConnect) { Text("Cancel") }
                        }
                    }

                    is SpotifyConnectState.Failed -> {
                        Text(connect.message, color = MaterialTheme.colorScheme.error)
                        Button(onClick = { actions.onConnectSpotify()?.let(uriHandler::openUri) }) { Text("Retry") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    SettingsSection("Appearance") {
        SettingsSwitchRow(
            title = "Use wallpaper colors",
            supporting = "Follow your phone's Material You palette instead of EVA's blue",
            checked = state.dynamicColor,
            onCheckedChange = actions.onDynamicColorChange,
        )
    }
}

/**
 * One field for both stored secrets. Neither value is ever read back into the UI, so a
 * saved secret shows only that it exists; a rejected one shows why.
 */
@Composable
private fun SecretField(
    label: String,
    savedTitle: String,
    savedSupporting: String,
    helper: String,
    saved: Boolean,
    saveLabel: String,
    onSave: (String) -> String?,
    onClear: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    if (saved) {
        SettingsRow(title = savedTitle, supporting = savedSupporting) {
            TextButton(onClick = onClear) { Text("Remove") }
        }
        return
    }
    SettingsBlock {
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                error = null
            },
            label = { Text(label) },
            supportingText = { Text(error ?: helper) },
            isError = error != null,
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                error = onSave(draft)
                if (error == null) draft = ""
            },
            enabled = draft.isNotBlank(),
        ) { Text(saveLabel) }
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
            Text("Asking OpenAI for a sign-in code…", style = MaterialTheme.typography.bodyMedium)
        }

        is SignInState.Waiting -> {
            val context = LocalContext.current
            // A fresh code deserves a fresh button, so the label cannot claim a stale copy.
            var copied by remember(signIn.userCode) { mutableStateOf(false) }
            Text("Open the sign-in page and enter this code:", style = MaterialTheme.typography.bodyMedium)
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

        is SignInState.Failed -> {
            Text(signIn.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onSignIn) { Text("Sign in with ChatGPT") }
        }

        is SignInState.Idle -> {
            Text(
                text = "Typed chat is then covered by your ChatGPT subscription.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onSignIn) { Text("Sign in with ChatGPT") }
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

@Preview(name = "Settings signed out", showBackground = true)
@Preview(
    name = "Settings signed out dark",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SettingsSignedOutPreview() {
    EvaTheme(dynamicColor = false) {
        SettingsScreen(state = SettingsUiState(), actions = SettingsActions(), onOpenDrawer = {})
    }
}

@Preview(name = "Settings signed in", showBackground = true)
@Composable
private fun SettingsSignedInPreview() {
    EvaTheme(dynamicColor = false) {
        SettingsScreen(
            state =
                SettingsUiState(
                    account = "ivan@example.com",
                    hasApiKey = true,
                    hasHostLink = true,
                    textModel = OpenAiModels.TEXT,
                    realtimeModel = OpenAiModels.REALTIME,
                ),
            actions = SettingsActions(),
            onOpenDrawer = {},
        )
    }
}
