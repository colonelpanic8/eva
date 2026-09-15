package com.colonelpanic.eva.ui.settings

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
internal fun MessagingSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    SettingsSection("Messaging") {
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
