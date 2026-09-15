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
internal fun GeneralExtensionSettingsSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    var source by remember(state.plugins.source) { mutableStateOf(state.plugins.source) }
    SettingsSection("General extension settings") {
        SettingsBlock {
            Text("These settings apply to extensions generally, not to a particular app.")
            TextButton(onClick = actions.onRefreshExtensions) { Text("Refresh installed extensions") }
            OutlinedTextField(
                value = source,
                onValueChange = { source = it },
                label = { Text("Extension index URL") },
                singleLine = true,
            )
            OutlinedButton(onClick = { actions.onRepositoryRefresh(source) }, enabled = !state.plugins.busy) {
                Text("Refresh extension repository")
            }
            Text("A timeout does not undo an action. EVA does not retry actions automatically.")
        }
        for (mode in InteractionMode.entries) {
            WaitField(
                "${mode.name.lowercase()} default wait",
                mode.name.lowercase(),
                state.waitDefaults[mode],
                actions,
            )
        }
    }
}

@Composable
internal fun ExtensionConfiguration(
    entry: com.colonelpanic.eva.data.PackageConfigurationEntry,
    actions: SettingsActions,
) {
    if (entry.credentialName != null) {
        SettingsBlock {
            var url by remember(entry.id, entry.origin) { mutableStateOf(entry.origin.orEmpty()) }
            var username by remember(entry.id) { mutableStateOf("") }
            var password by remember(entry.id) { mutableStateOf("") }
            var error by remember(entry.id) { mutableStateOf<String?>(null) }
            Text("Server configuration")
            Text("Credentials stay encrypted on this phone. Saving a URL approves only that origin.")
            Text("Credential reference: ${entry.credentialName}")
            if (entry.origin != null && !entry.credentialAvailable) {
                Text("Credentials are required on this device.")
            }
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
            if (entry.origin != null) {
                Text("Credentials saved. Values are never read back into these fields.")
                TextButton(onClick = { actions.onClearPackageServer(entry.id) }) { Text("Remove server credentials") }
            }
        }
    }
    WaitField("Wait override (blank uses extension default)", entry.id, entry.waitMillis, actions)
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
            supportingText = { Text(error ?: "Seconds, 1–60. Extension override > action default > mode default.") },
        )
        TextButton(onClick = { error = actions.onSaveWait(id, draft) }) { Text("Save wait") }
    }
}
