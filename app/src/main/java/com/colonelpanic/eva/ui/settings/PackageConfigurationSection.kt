package com.colonelpanic.eva.ui.settings

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.colonelpanic.eva.capability.InteractionMode

@Composable
internal fun PackageConfigurationSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val hasServers = state.packages.any { it.credentialName != null }
    SettingsSection(if (hasServers) "Package servers and wait budgets" else "Extension wait budgets") {
        SettingsBlock {
            if (hasServers) {
                Text(
                    "Server credentials stay encrypted on this phone. Saving a URL approves that origin only; changing it requires enabling the package again.",
                )
            }
            Text("Reads disclose returned data to the configured model. A timeout does not undo an action. No automatic retries.")
        }
        for (mode in InteractionMode.entries) {
            WaitField(
                "${mode.name.lowercase()} default wait",
                mode.name.lowercase(),
                state.waitDefaults[mode],
                actions,
            )
        }
        for (entry in state.packages) {
            SettingsRow(
                entry.title,
                if (entry.credentialName ==
                    null
                ) {
                    "Uses installed Android apps; no server credentials needed"
                } else {
                    when {
                        entry.origin == null -> "Server not configured"
                        entry.credentialAvailable -> entry.origin
                        else -> "${entry.origin} · credentials required on this device"
                    }
                },
            )
            if (entry.credentialName != null) {
                SettingsBlock {
                    var url by remember(entry.id, entry.origin) { mutableStateOf(entry.origin.orEmpty()) }
                    var username by remember(entry.id) { mutableStateOf("") }
                    var password by remember(entry.id) { mutableStateOf("") }
                    var error by remember(entry.id) { mutableStateOf<String?>(null) }
                    Text("Credential reference: ${entry.credentialName}")
                    OutlinedTextField(url, { url = it }, label = { Text("HTTPS server URL (origin only)") }, singleLine = true)
                    OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true)
                    OutlinedTextField(
                        password,
                        { password = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    error?.let { Text(it) }
                    OutlinedButton(onClick = {
                        error = actions.onSavePackageServer(entry.id, url, username, password)
                        if (error == null) {
                            username = ""
                            password = ""
                        }
                    }) { Text("Save server and credentials") }
                    if (entry.credentialAvailable) {
                        Text("Credentials saved. Values are never read back into these fields.")
                    }
                    if (entry.origin != null) {
                        TextButton(onClick = { actions.onClearPackageServer(entry.id) }) { Text("Remove server credentials") }
                    }
                }
            }
            WaitField("${entry.title} wait override (blank uses package default)", entry.id, entry.waitMillis, actions)
        }
    }
}

@Composable
private fun WaitField(
    label: String,
    id: String,
    millis: Long?,
    actions: SettingsActions,
) {
    SettingsBlock {
        var draft by remember(id, millis) { mutableStateOf(millis?.div(1000)?.toString().orEmpty()) }
        var error by remember(id) { mutableStateOf<String?>(null) }
        OutlinedTextField(
            draft,
            { draft = it },
            label = { Text(label) },
            singleLine = true,
            supportingText = { Text(error ?: "Seconds, 1–60. Extension override > package default > mode default.") },
        )
        TextButton(onClick = { error = actions.onSaveWait(id, draft) }) { Text("Save wait") }
    }
}
