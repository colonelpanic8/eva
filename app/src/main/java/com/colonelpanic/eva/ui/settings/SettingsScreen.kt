package com.colonelpanic.eva.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.adapters.android.DeviceControlHost
import com.colonelpanic.eva.conversation.prompt.VoiceCallMode
import com.colonelpanic.eva.data.configuration.ConfigurationStatus
import com.colonelpanic.eva.data.configuration.EvaConfigurationCodec
import com.colonelpanic.eva.data.configuration.SshRemote
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
    activeTaskCount: Int = 0,
    onRunningWork: () -> Unit = {},
    showScreenControl: Boolean = false,
    onScreenControlShown: () -> Unit = {},
) {
    val scroll = rememberScrollState()
    var screenControlTop by remember { mutableIntStateOf(-1) }
    LaunchedEffect(showScreenControl, screenControlTop) {
        if (showScreenControl && screenControlTop >= 0) {
            scroll.animateScrollTo(screenControlTop)
            onScreenControlShown()
        }
    }
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
                    .verticalScroll(scroll),
        ) {
            ConfigurationSection(state, actions)
            SettingsDivider()
            BackgroundWorkSection(state, actions, activeTaskCount, onRunningWork)
            SettingsDivider()
            AccountSection(state, actions)
            SettingsDivider()
            ModelsSection(state, actions)
            SettingsDivider()
            WebResearchSection(state, actions)
            SettingsDivider()
            AssistantSection(state, actions)
            SettingsDivider()
            MediaSection(state, actions)
            Box(Modifier.onGloballyPositioned { screenControlTop = it.positionInParent().y.toInt() }) {
                ScreenControlSection(state, actions)
            }
            SettingsDivider()
            SpotifySection(state, actions)
            SettingsDivider()
            AppearanceSection(state, actions)
            SettingsDivider()
            DiagnosticsSection(state, actions)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BackgroundWorkSection(
    state: SettingsUiState,
    actions: SettingsActions,
    count: Int,
    onRunningWork: () -> Unit,
) {
    SettingsSection("Background work") {
        SettingsRow(title = "Running work", supporting = plural(count, "active task")) {
            TextButton(onClick = onRunningWork) { Text("Open") }
        }
        SecondsSettingRow(
            title = "Flag as stuck after",
            supporting = "Tasks with no progress are flagged, never stopped.",
            seconds = state.stallPeriodSeconds.toLong(),
            onSave = { draft ->
                val seconds = draft.toIntOrNull()
                if (seconds == null || seconds <= 0) {
                    "Enter whole seconds above zero."
                } else {
                    actions.onStallPeriodSeconds(seconds)
                    null
                }
            },
        )
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
    val configuration = state.configuration
    var gitExpanded by rememberSaveable { mutableStateOf(false) }
    val sshRemote = remember(remote) { runCatching { SshRemote.parse(remote.trim()) }.getOrNull() }
    SettingsSection("Your configuration") {
        SettingsBlock {
            SettingsNote(
                configuration.linkedFolder?.let { "Saved to $it/${EvaConfigurationCodec.FILE_NAME}" }
                    ?: "Keep your settings in a synced folder, or let EVA manage a Git checkout.",
            )
            configuration.message?.let { SettingsNote(it, error = configuration.isError) }
            configuration.setupRequired.forEach { SettingsNote("Setup required: $it", error = true) }
        }
        if (!configuration.gitEnabled) {
            SettingsRow(title = "Synced folder", supporting = configuration.linkedFolder ?: "Another app keeps the folder in sync") {
                Row {
                    if (configuration.linkedFolder != null) {
                        TextButton(onClick = actions.onReloadConfiguration) { Text("Reload") }
                    }
                    TextButton(onClick = actions.onSelectConfigurationFolder) {
                        Text(if (configuration.linkedFolder == null) "Choose" else "Change")
                    }
                }
            }
        }
        ExpandableSettingsRow(
            title = "Managed Git",
            supporting =
                if (configuration.gitEnabled) {
                    "${git.remoteUrl.removePrefix("https://")} · ${configuration.gitCondition.name.lowercase().replace('_', ' ')}"
                } else {
                    "EVA keeps its own checkout, over HTTPS or SSH"
                },
            expanded = gitExpanded,
            onExpandedChange = { gitExpanded = it },
            trailing = {
                Switch(
                    checked = configuration.gitEnabled,
                    onCheckedChange = {
                        if (it) gitExpanded = true
                        actions.onGitEnabled(it)
                    },
                    modifier = Modifier.semantics { contentDescription = "Managed Git" },
                )
            },
        ) {
            SettingsBlock {
                OutlinedTextField(
                    value = remote,
                    onValueChange = { remote = it },
                    label = { Text("Remote URL (HTTPS or SSH)") },
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
                if (sshRemote == null) {
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
                        supportingText = {
                            Text(
                                if (git.tokenPresent) {
                                    "A token is saved on this phone."
                                } else {
                                    "Needed to push. Stored encrypted on this phone."
                                },
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    GitSshKeySettings(sshRemote, configuration, actions)
                }
                inputError?.let { SettingsNote(it, error = true) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !configuration.busy,
                        onClick = {
                            inputError = actions.onSaveGit(remote, branch, authorName, authorEmail, username, token)
                            if (inputError == null) token = ""
                        },
                    ) { Text("Save & connect") }
                    if (configuration.gitEnabled && configuration.linkedFolder != null) {
                        OutlinedButton(enabled = !configuration.busy, onClick = actions.onReloadConfiguration) { Text("Sync") }
                    }
                    if (git.tokenPresent && sshRemote == null) {
                        TextButton(onClick = actions.onClearGitToken) { Text("Remove token") }
                    }
                }
            }
        }
    }
}

@Composable
private fun GitSshKeySettings(
    remote: SshRemote,
    configuration: ConfigurationStatus,
    actions: SettingsActions,
) {
    val context = LocalContext.current
    val publicKey = configuration.sshPublicKey
    var copied by remember(publicKey) { mutableStateOf(false) }
    var confirmRegenerate by remember(publicKey) { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    if (publicKey == null) {
        SettingsNote(
            "EVA signs in to ${remote.host} with an SSH key it keeps on this phone. Create a new key, or import an existing " +
                "private key file. No token is needed.",
        )
    } else {
        SettingsNote(
            when {
                configuration.sshKeyImported -> {
                    "EVA signs in with the key you imported. If ${remote.host} already accepts it, nothing else is needed; " +
                        "otherwise add this public key with write access to the repository."
                }

                remote.host == "github.com" -> {
                    "Add this public key to the repository on GitHub: Settings → Deploy keys → Add deploy key, and check Allow write access."
                }

                else -> {
                    "Add this public key to ${remote.host} with write access to the repository, for example as a deploy key."
                }
            },
        )
        SelectionContainer {
            Text(publicKey, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { copied = copyToClipboard(context, publicKey, "EVA SSH public key") }) {
                Text(if (copied) "Copied" else "Copy key")
            }
            OutlinedButton(onClick = { shareText(context, publicKey, "EVA SSH public key") }) { Text("Share") }
            if (!confirmRegenerate) {
                TextButton(enabled = !configuration.busy, onClick = { confirmRegenerate = true }) { Text("Regenerate key") }
            }
        }
        if (confirmRegenerate) {
            SettingsNote("A new key replaces this one; the current key stops working wherever it was added.", error = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !configuration.busy, onClick = actions.onRegenerateGitSshKey) { Text("Replace key") }
                TextButton(onClick = { confirmRegenerate = false }) { Text("Cancel") }
            }
        }
    }
    OutlinedTextField(
        value = passphrase,
        onValueChange = { passphrase = it },
        label = { Text("Key passphrase (if the file has one)") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (publicKey == null) {
            OutlinedButton(enabled = !configuration.busy, onClick = actions.onRegenerateGitSshKey) { Text("Create SSH key") }
        }
        OutlinedButton(enabled = !configuration.busy, onClick = {
            actions.onImportGitSshKey(passphrase)
            passphrase = ""
        }) { Text("Import key file") }
    }
    configuration.sshHostKeys
        .filter { it.host == remote.host && it.port == remote.port }
        .forEach { entry ->
            SettingsNote("Trusted host key for ${entry.label}: ${entry.fingerprint}")
            TextButton(enabled = !configuration.busy, onClick = { actions.onForgetGitHostKey(entry) }) { Text("Forget host key") }
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
            title = "OpenAI API key",
            helper = "Pay per use instead of a ChatGPT subscription",
            label = "OpenAI API key",
            saved = state.hasApiKey,
            onSave = actions.onSaveApiKey,
            onClear = actions.onClearApiKey,
        )
        SecretField(
            title = "Paired host",
            helper = "Route conversations through a host you paired",
            label = "Paired host link",
            saved = state.hasHostLink,
            savedSupporting = "Link saved on this phone",
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
            SettingsNote("Changes apply from the next connection.")
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
            title = "End calls after one request",
            supporting =
                if (state.callMode == VoiceCallMode.ONE_REQUEST) {
                    "Once a request is done, EVA says a short closing line and hangs up. Applies to every voice call."
                } else {
                    "Every voice call stays open until you say goodbye or hang up. Ask once still ends after one request."
                },
            checked = state.callMode == VoiceCallMode.ONE_REQUEST,
            onCheckedChange = actions.onEndAfterOneRequestChange,
        )
        if (state.nativeCallEndings.isNotEmpty()) {
            SettingsBlock { SettingsNote("After these succeed in a call. A failed action always leaves the call open.") }
        }
        state.nativeCallEndings.forEach { action ->
            SettingsRow(title = action.title) {
                CallEndingPicker(action.declared, state.callEndings[action.id]) { actions.onCallEnding(action.id, it) }
            }
        }
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
            title = if (state.canSeeMediaSessions) "Sees what's playing" else "Play and skip only",
            supporting =
                if (state.canSeeMediaSessions) {
                    "EVA can tell which app is playing what, and confirm its controls worked."
                } else {
                    "To see what's playing, EVA needs notification access. Android grants nothing narrower, so " +
                        "your notifications reach EVA too; it reads messages only if you allow that under Messaging."
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
    SettingsSection("Screen control") {
        SettingsSwitchRow(
            title = "Let EVA read and tap the screen",
            supporting = "Uses the first ready backend below. Off hides screen control from the model entirely.",
            checked = state.screenControlEnabled,
            onCheckedChange = actions.onScreenControlChange,
        )
        if (state.canControlScreen && state.shizukuAccess != null && state.shizukuAccess != DeviceControlHost.ALLOWED) {
            SettingsRow(title = "Shizuku", supporting = state.shizukuAccess) {
                TextButton(onClick = actions.onAllowShizuku) { Text("Allow") }
            }
        }
        DeviceTaskBackendList(
            backends = state.deviceTask.backends,
            routes = state.screenControlStatus.routes.associateBy { it.backend },
            repairing = state.screenControlRepairing,
            onChange = { actions.onDeviceTaskChange(state.deviceTask.copy(backends = it)) },
            onRepair = { backend -> actions.onRepairScreenControl(backend) {} },
        )
        SecretField(
            title = "Portal token",
            helper = "The bearer token shown in the Portal app",
            label = "Portal bearer token",
            saved = state.hasPortalToken,
            onSave = {
                actions.onPortalToken(it)
                null
            },
            onClear = { actions.onPortalToken("") },
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
    var expanded by rememberSaveable { mutableStateOf(false) }
    SettingsSection("Spotify") {
        if (state.spotifyAccount != null) {
            SettingsRow(
                title = state.spotifyAccount,
                supporting = if (state.spotifyPremium == false) "Connected · Queueing songs needs Spotify Premium" else "Connected",
            ) {
                TextButton(onClick = actions.onDisconnectSpotify) { Text("Disconnect") }
            }
        } else if (state.spotifyClientId != null) {
            SettingsBlock {
                when (val connect = state.spotifyConnect) {
                    is SpotifyConnectState.Idle -> {
                        Button(onClick = { actions.onConnectSpotify()?.let(uriHandler::openUri) }) { Text("Connect Spotify") }
                    }

                    is SpotifyConnectState.Waiting -> {
                        SettingsNote("Finish signing in with Spotify in your browser.")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { uriHandler.openUri(connect.authorizationUrl) }) { Text("Open sign-in page") }
                            TextButton(onClick = actions.onCancelSpotifyConnect) { Text("Cancel") }
                        }
                    }

                    is SpotifyConnectState.Failed -> {
                        SettingsNote(connect.message, error = true)
                        Button(onClick = { actions.onConnectSpotify()?.let(uriHandler::openUri) }) { Text("Retry") }
                    }
                }
            }
        }
        ExpandableSettingsRow(
            title = "Client ID",
            supporting = state.spotifyClientId ?: "Needed to queue songs through Spotify",
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            SettingsBlock {
                SettingsNote(
                    "Create a free app at developer.spotify.com/dashboard with the redirect URI eva://spotify, then paste its Client ID.",
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
                Button(
                    onClick = {
                        error = actions.onSaveSpotifyClientId(clientId)
                        if (error == null) expanded = false
                    },
                    enabled = clientId.trim() != state.spotifyClientId.orEmpty(),
                ) { Text("Save") }
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
            supporting = "Follow your phone's palette instead of EVA's blue",
            checked = state.dynamicColor,
            onCheckedChange = actions.onDynamicColorChange,
        )
    }
}

@Composable
private fun DiagnosticsSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    SettingsSection("Diagnostics") {
        SettingsSwitchRow(
            title = "Verbose logging",
            supporting = "Also record speech and transcript timing. Message text and credentials are never logged.",
            checked = state.verboseLogging,
            onCheckedChange = actions.onVerboseLogging,
        )
        SettingsBlock {
            SettingsNote(
                "Exports are JSON for the share sheet. Credentials are redacted; a thread export includes its messages and actions.",
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.onExportLogs) { Text("Export logs") }
                OutlinedButton(onClick = actions.onShareThreadDiagnostics, enabled = state.hasCurrentThread) { Text("Share this thread") }
            }
        }
    }
}

/**
 * A stored secret. None is ever read back into the UI, so a saved one shows only that it exists
 * and a rejected one shows why. A secret EVA cannot report as saved takes no [onClear].
 */
@Composable
private fun SecretField(
    title: String,
    helper: String,
    label: String,
    onSave: (String) -> String?,
    saved: Boolean = false,
    savedSupporting: String = "Saved on this phone",
    onClear: (() -> Unit)? = null,
) {
    if (saved && onClear != null) {
        SettingsRow(title = title, supporting = savedSupporting) {
            TextButton(onClick = onClear) { Text("Remove") }
        }
        return
    }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExpandableSettingsRow(title = title, supporting = helper, expanded = expanded, onExpandedChange = { expanded = it }) {
        SettingsBlock {
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    error = null
                },
                label = { Text(label) },
                supportingText = error?.let { message -> { Text(message) } },
                isError = error != null,
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    error = onSave(draft)
                    if (error == null) {
                        draft = ""
                        expanded = false
                    }
                },
                enabled = draft.isNotBlank(),
            ) { Text("Save on this phone") }
        }
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
    text: String,
    label: String = "EVA sign-in code",
): Boolean {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    return true
}

private fun shareText(
    context: Context,
    text: String,
    title: String,
) {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
