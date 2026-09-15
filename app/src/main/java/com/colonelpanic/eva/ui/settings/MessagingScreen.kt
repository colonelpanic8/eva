package com.colonelpanic.eva.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.data.MessagingPreferences
import com.colonelpanic.eva.messaging.MessagingApp
import com.colonelpanic.eva.ui.MenuButton
import com.colonelpanic.eva.ui.evaTopAppBarColors
import com.colonelpanic.eva.ui.theme.EvaTheme

private const val MAX_LOOKUP_RETRIES = 10

/**
 * Reaching a person: which grants EVA holds, how hard it looks for a spoken name, which apps it
 * may read and reply through, and the numbers it kept. None of this generalizes to another
 * capability the way an extension does, and none of it is a preference about EVA itself, so it
 * reads better as one screen about messaging than as four groups buried in Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagingScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onOpenDrawer: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Messaging") },
                navigationIcon = { MenuButton(onOpenDrawer) },
                colors = evaTopAppBarColors(),
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
        ) {
            PermissionSection(state, actions)
            SettingsDivider()
            ContactLookupSection(state, actions)
            SettingsDivider()
            NotificationMessagingSection(state, actions)
            SettingsDivider()
            RememberedNumbersSection(state, actions)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * EVA asks for these once, when it opens, so a later denial is otherwise invisible: texting simply
 * stops working with no explanation. App settings is the only screen that can hand them back.
 */
@Composable
private fun PermissionSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    if (state.messagingPermissions.isEmpty()) return
    val missing = state.messagingPermissions.filterNot { it.granted }
    SettingsSection("Phone permissions") {
        SettingsRow(
            title =
                if (missing.isEmpty()) {
                    "Contacts and messages are readable"
                } else {
                    "Missing " + missing.joinToString { it.label.lowercase() }
                },
            supporting =
                if (missing.isEmpty()) {
                    state.messagingPermissions.joinToString { it.label }
                } else {
                    "EVA asks for these when it opens. Without them it cannot find a contact or send a text."
                },
        ) {
            TextButton(onClick = actions.onOpenAppSettings) { Text("Change") }
        }
    }
}

/**
 * Spoken names are transcribed with the wrong spelling often enough that EVA searches plausible
 * variants before giving up. This is the budget for those extra searches, and nothing else uses
 * it, so it belongs with contacts rather than under a general voice heading.
 */
@Composable
private fun ContactLookupSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    SettingsSection("Contact lookup") {
        SettingsRow(
            title = "Extra lookup attempts",
            supporting = "How many more times a spoken name is searched before EVA asks who you meant",
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
private fun NotificationMessagingSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    SettingsSection("Other messaging apps") {
        SettingsBlock {
            Text(
                "SMS/MMS uses Android's messaging permissions. Other apps can expose recent messages and reply actions through notifications.",
            )
        }
        SettingsSwitchRow(
            "Read messaging notifications",
            "Let EVA use messaging notification excerpts in conversations with your configured model. This is not full chat history. Replies need separate app approval.",
            state.messaging.enabled,
            actions.onMessagingEnable,
        )
        if (state.messaging.enabled) {
            SettingsRow(
                "Android notification access",
                if (state.canSeeMediaSessions) {
                    "Granted. Only active messaging notifications are used."
                } else {
                    "Grant access, then refresh to discover message notifications."
                },
            ) {
                TextButton(onClick = actions.onOpenMediaControlSettings) { Text("Change") }
            }
            SettingsBlock {
                TextButton(onClick = actions.onMessagingRefresh) { Text("Refresh messaging apps") }
                if (state.messagingApps.isEmpty()) {
                    Text(
                        "No messaging apps discovered yet. Receive a message with a notification, then refresh.",
                    )
                }
            }
            state.messagingApps.forEach { app ->
                SettingsSwitchRow(
                    "Allow replies in " + app.title,
                    app.packageName + ". Replies are sent through the app's notification action; delivery is not confirmed.",
                    app.identity in state.messaging.replies,
                    { actions.onMessagingReply(app.identity, it) },
                )
            }
        }
    }
}

/**
 * The one messaging leftover that outlives a conversation, so it is forgettable from here. Only
 * the number and when it was reached are held, never a name or anything that was said.
 */
@Composable
private fun RememberedNumbersSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    SettingsSection("Remembered numbers") {
        SettingsRow(
            title =
                when (state.rememberedNumbers) {
                    0 -> "No numbers remembered"
                    1 -> "1 number remembered"
                    else -> "${state.rememberedNumbers} numbers remembered"
                },
            supporting = "The numbers EVA last texted or called, kept so repeating a name reaches the same person.",
        ) {
            if (state.rememberedNumbers > 0) {
                TextButton(onClick = actions.onForgetRememberedNumbers) { Text("Forget") }
            }
        }
    }
}

@Preview(name = "Messaging", showBackground = true)
@Preview(
    name = "Messaging dark",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun MessagingPreview() {
    EvaTheme(dynamicColor = false) {
        MessagingScreen(
            state =
                SettingsUiState(
                    messaging = MessagingPreferences(enabled = true, replies = setOf("signal")),
                    messagingApps =
                        listOf(
                            MessagingApp(identity = "signal", title = "Signal", packageName = "org.thoughtcrime.securesms"),
                        ),
                    messagingPermissions =
                        listOf(
                            PermissionStatus("Read contacts", granted = true),
                            PermissionStatus("Read SMS", granted = true),
                            PermissionStatus("Send SMS", granted = false),
                        ),
                    rememberedNumbers = 12,
                    voiceLookupRetries = 5,
                    canSeeMediaSessions = true,
                ),
            actions = SettingsActions(),
            onOpenDrawer = {},
        )
    }
}
