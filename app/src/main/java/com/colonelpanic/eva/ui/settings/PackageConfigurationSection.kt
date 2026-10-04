package com.colonelpanic.eva.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.capability.InteractionMode
import com.colonelpanic.eva.data.PackageConfigurationEntry

@Composable
internal fun GeneralExtensionSettingsSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    SettingsSection("Waiting for results") {
        SettingsBlock {
            SettingsNote(
                "How long EVA waits for a result. After a timeout the outcome may be unknown, and EVA does not " +
                    "retry. A wait set on an extension takes precedence.",
            )
        }
        for (mode in InteractionMode.entries) {
            SecondsSettingRow(
                title = if (mode == InteractionMode.VOICE) "In voice calls" else "In typed chats",
                supporting = "1–60 seconds",
                seconds = state.waitDefaults[mode]?.div(1000),
                onSave = { actions.onSaveWait(mode.name.lowercase(), it) },
            )
        }
    }
    SettingsDivider()
    SettingsSection("Discovery") {
        SettingsRow("Installed extension apps", "Look again for apps that provide extensions.") {
            TextButton(onClick = actions.onRefreshExtensions) { Text("Rescan") }
        }
    }
}

@Composable
internal fun ExtensionConfiguration(
    entry: PackageConfigurationEntry,
    actions: SettingsActions,
    showWait: Boolean = true,
) {
    if (entry.credentialName != null) ServerConfiguration(entry, actions)
    if (showWait) {
        SecondsSettingRow(
            title = "Wait for results",
            supporting = "Blank uses the default",
            seconds = entry.waitMillis?.div(1000),
            onSave = { actions.onSaveWait(entry.id, it) },
            placeholder = "–",
        )
    }
}

@Composable
private fun ServerConfiguration(
    entry: PackageConfigurationEntry,
    actions: SettingsActions,
) {
    var serviceName by remember(entry.id, entry.sourceOrigin, entry.serviceName) {
        mutableStateOf(entry.serviceName ?: suggestedServiceName(entry.id))
    }
    var url by remember(entry.id, entry.origin) { mutableStateOf(entry.origin.orEmpty()) }
    var username by remember(entry.id) { mutableStateOf("") }
    var password by remember(entry.id) { mutableStateOf("") }
    var error by remember(entry.id) { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Server", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        SettingsNote(
            when {
                entry.origin == null -> "Not set up. Choose the server this extension talks to."
                !entry.credentialAvailable -> "Credentials are needed on this device."
                else -> "Credentials saved for ${entry.origin}. They're never shown again."
            },
            error = entry.origin != null && !entry.credentialAvailable,
        )
        OutlinedTextField(
            url,
            { url = it },
            label = { Text("HTTPS server URL") },
            supportingText = { Text("Origin only, such as https://example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (entry.credentialScheme == "basic") {
            OutlinedTextField(
                username,
                { username = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            password,
            { password = it },
            label = { Text(if (entry.credentialScheme == "bearer") "Bearer token" else "Password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            serviceName,
            { serviceName = it },
            label = { Text("Service name") },
            supportingText = { Text("Extensions with the same service name share this server and credential.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { SettingsNote(it, error = true) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                error = actions.onSavePackageServer(entry.id, entry.sourceOrigin, serviceName, url, username, password)
                if (error == null) {
                    username = ""
                    password = ""
                }
            }) { Text("Save") }
            if (entry.origin != null) {
                TextButton(onClick = { actions.onClearPackageServer(entry.id, entry.sourceOrigin) }) { Text("Forget server") }
            }
        }
        Text(
            "Package from ${entry.sourceOrigin} · credential “${entry.credentialName}” · stored encrypted on this phone",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun suggestedServiceName(instance: String) = "package-$instance"
