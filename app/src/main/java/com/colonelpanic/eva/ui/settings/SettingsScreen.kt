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
import androidx.compose.material3.Slider
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
import com.colonelpanic.eva.ui.MenuButton
import com.colonelpanic.eva.ui.ModelPicker
import com.colonelpanic.eva.ui.ReasoningEffortPicker
import com.colonelpanic.eva.ui.evaTopAppBarColors
import com.colonelpanic.eva.ui.theme.EvaTheme

private const val MAX_LOOKUP_RETRIES = 10

/**
 * Everything that outlives a session: credentials, models, voice defaults, appearance.
 * It is reachable while connected, which the old in-header panel was not.
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
            AccountSection(state, actions)
            SettingsDivider()
            ModelsSection(state, actions)
            SettingsDivider()
            VoiceSection(state, actions)
            SettingsDivider()
            AppearanceSection(state, actions)
            SettingsDivider()
            SettingsSection("About") {
                SettingsRow(title = "EVA", supporting = state.version?.let { "Version $it" } ?: "Version unavailable")
            }
            Spacer(Modifier.height(24.dp))
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
            ReasoningEffortPicker(state.reasoningEffort, OpenAiModels.REASONING_EFFORTS, actions.onSelectReasoningEffort)
            Text(
                text = "A change applies to the next connection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VoiceSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    SettingsSection("Voice") {
        SettingsRow(
            title = "Extra lookup attempts",
            supporting = "How many more times a spoken name is searched before EVA gives up",
        ) {
            Text(state.voiceLookupRetries.toString(), style = MaterialTheme.typography.titleMedium)
        }
        SettingsBlock {
            Slider(
                value = state.voiceLookupRetries.toFloat(),
                onValueChange = { actions.onVoiceLookupRetriesChange(it.toInt()) },
                valueRange = 0f..MAX_LOOKUP_RETRIES.toFloat(),
                steps = MAX_LOOKUP_RETRIES - 1,
                modifier = Modifier.fillMaxWidth(),
            )
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
        SettingsScreen(state = SettingsUiState(version = "0.10.0"), actions = SettingsActions(), onOpenDrawer = {})
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
                    voiceLookupRetries = 5,
                    version = "0.10.0",
                ),
            actions = SettingsActions(),
            onOpenDrawer = {},
        )
    }
}
