package com.colonelpanic.eva.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.capability.extensions.Effect

@Composable
internal fun ExtensionsSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val extensions = state.extensions
    SettingsSection("Your extensions") {
        SettingsBlock {
            Text(
                "Enable only extensions you trust. Read actions can disclose private data to your configured model. " +
                    "Apps declare their own effects; EVA cannot verify those claims.",
            )
            Text("Newly enabled actions appear on your next connection. Search and complete require separate requests.")
            extensions.error?.let { Text(it) }
            if (extensions.entries.isEmpty()) Text("No extensions installed or discovered yet.")
        }
        for (entry in extensions.entries) {
            val installed = entry.installed
            val descriptor = installed.descriptor
            val packageId = installed.identity?.instanceId?.removePrefix("package:")
            val configurations = state.packages.filter { it.id == packageId }
            val repositoryInstallation = state.plugins.installed.find { it.identity.id == packageId }
            var expanded by rememberSaveable(entry.key) { mutableStateOf(false) }
            SettingsRow(
                title = descriptor?.title ?: installed.packageName,
                leading = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            if (expanded) "Collapse actions" else "Expand actions",
                        )
                        InstalledAppIcon(installed.androidPackages)
                    }
                },
                onClick = { expanded = !expanded },
                supporting =
                    installed.packageName + "\n" +
                        (
                            installed.problem
                                ?: "${descriptor?.capabilities?.size ?: 0} actions · ${if (expanded) "Hide" else "Show"} action permissions"
                        ),
            ) {
                Switch(
                    checked = entry.enabled,
                    enabled = descriptor != null && (entry.enabled || installed.problem == null),
                    onCheckedChange = { actions.onExtensionEnable(entry.key, it) },
                )
            }
            if (expanded) {
                Column(Modifier.padding(start = 32.dp)) {
                    descriptor?.capabilities?.forEach { capability ->
                        val unavailable = state.extensionOverflow["${installed.capabilityPrefix}.${capability.name}"]
                        if (unavailable != null) SettingsRow(capability.title, unavailable)
                        if (capability.effect == Effect.READ) {
                            SettingsRow(capability.title, "Provider claims read-only. ${capability.description}")
                        } else {
                            SettingsRow(
                                capability.title,
                                when (capability.effect) {
                                    Effect.WRITE -> "Changes data. "
                                    Effect.HANDOFF -> "Hands off to another app. "
                                    else -> "Unknown effects; treated as a write. "
                                } + capability.description,
                            ) {
                                Switch(
                                    checked = capability.name in entry.mutations,
                                    enabled = entry.enabled && (installed.problem == null || capability.name in entry.mutations),
                                    onCheckedChange = { actions.onExtensionMutation(entry.key, capability.name, it) },
                                )
                            }
                        }
                    }
                    configurations.flatMap { it.contentAuthorities }.distinct().forEach { authority ->
                        state.contentProviders[authority]?.let { access ->
                            SettingsBlock {
                                Text("Content provider: $authority")
                                Text(access.problem ?: "Android access is available. The provider also enforces its own caller policy.")
                                if (access.canRequest) {
                                    Text(
                                        "Allowing this read can disclose provider data to your configured model. Android access must be authorized on each device.",
                                    )
                                    TextButton(onClick = { actions.onContentPermission(authority) }) { Text("Allow provider reads") }
                                }
                                if (access.permission != null) {
                                    TextButton(onClick = actions.onOpenAppSettings) { Text("Android permission settings") }
                                }
                            }
                        }
                    }
                    configurations.forEachIndexed { index, configuration ->
                        ExtensionConfiguration(configuration, actions, showWait = index == configurations.lastIndex)
                    }
                    if (repositoryInstallation != null) {
                        TextButton(
                            onClick = { actions.onPluginRemove(repositoryInstallation.identity.id) },
                            enabled = !state.plugins.busy,
                        ) { Text("Remove extension") }
                    }
                }
            }
        }
    }
}
