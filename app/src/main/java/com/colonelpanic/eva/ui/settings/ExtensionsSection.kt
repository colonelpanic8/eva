package com.colonelpanic.eva.ui.settings

import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.colonelpanic.eva.capability.extensions.Effect
import com.colonelpanic.eva.capability.extensions.ExtensionSettings

@Composable
internal fun ExtensionsSection(
    state: ExtensionSettings,
    actions: SettingsActions,
    overflow: Map<String, String>,
) {
    SettingsSection("Installed extensions") {
        SettingsBlock {
            Text(
                "Enable only extensions you trust. Read actions can disclose private data to your configured model. " +
                    "Apps declare their own effects; EVA cannot verify those claims.",
            )
            Text("Newly enabled actions appear on your next connection. Search and complete require separate requests.")
            state.error?.let { Text(it) }
            TextButton(onClick = actions.onRefreshExtensions) { Text("Refresh extensions") }
            if (state.entries.isEmpty()) Text("No extension providers discovered yet.")
        }
        for (entry in state.entries) {
            val installed = entry.installed
            val descriptor = installed.descriptor
            SettingsRow(
                title = descriptor?.title ?: installed.packageName,
                leading = { InstalledAppIcon(installed.androidPackages) },
                supporting =
                    installed.packageName + "\n" +
                        (installed.problem ?: "Enabling grants claimed read actions. Writes need separate permission."),
            ) {
                Switch(
                    checked = entry.enabled,
                    enabled = descriptor != null && (entry.enabled || installed.problem == null),
                    onCheckedChange = { actions.onExtensionEnable(entry.key, it) },
                )
            }
            descriptor?.capabilities?.forEach { capability ->
                val unavailable = overflow["${installed.capabilityPrefix}.${capability.name}"]
                if (unavailable != null) SettingsRow(capability.title, unavailable)
                if (capability.effect == Effect.READ) {
                    SettingsRow(capability.title, "Provider claims read-only. ${capability.description}")
                } else {
                    SettingsRow(
                        capability.title,
                        "${if (capability.effect == Effect.WRITE) "Changes data" else "Unknown effects; treated as a write"}. " +
                            capability.description,
                    ) {
                        Switch(
                            checked = capability.name in entry.mutations,
                            enabled = entry.enabled && (installed.problem == null || capability.name in entry.mutations),
                            onCheckedChange = { actions.onExtensionMutation(entry.key, capability.name, it) },
                        )
                    }
                }
            }
        }
    }
}
