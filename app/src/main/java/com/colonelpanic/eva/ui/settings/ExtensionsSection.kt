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
import com.colonelpanic.eva.capability.CallEnding
import com.colonelpanic.eva.capability.extensions.Effect
import com.colonelpanic.eva.capability.extensions.ExtensionSettingsEntry

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
        val folded = extensions.entries.filter { it.supersession != null }.groupBy { it.supersession?.owner }
        for (entry in extensions.entries.filter { it.supersession == null }) {
            ExtensionEntry(entry, folded[entry.installed.identity?.instanceId].orEmpty(), state, actions)
        }
    }
}

/** One extension row; a package the app's own extension speaks for is folded inside it, showing only what remains. */
@Composable
private fun ExtensionEntry(
    entry: ExtensionSettingsEntry,
    folded: List<ExtensionSettingsEntry>,
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val installed = entry.installed
    val descriptor = installed.descriptor
    val capabilities = descriptor?.capabilities.orEmpty().filter { it.name !in entry.supersession?.actions.orEmpty() }
    val packageId = installed.identity?.instanceId?.removePrefix("package:")
    val configurations = state.packages.filter { it.id == packageId }
    val repositoryInstallation = state.plugins.installed.find { it.identity.id == packageId }
    var expanded by rememberSaveable(entry.key) { mutableStateOf(false) }
    SettingsRow(
        title = descriptor?.title?.let { if (entry.supersession != null) "More $it actions" else it } ?: installed.packageName,
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
                        ?: "${capabilities.size} actions · ${if (expanded) "Hide" else "Show"} action permissions"
                ),
    ) {
        Switch(
            checked = entry.enabled,
            enabled = descriptor != null && (entry.enabled || installed.problem == null),
            onCheckedChange = { value ->
                actions.onExtensionEnable(entry.key, value)
                // Turning an extension off also tells the next catalog refresh to leave it off.
                repositoryInstallation?.let { actions.onExtensionAutoEnable(it.definition.id, value) }
            },
        )
    }
    if (expanded) {
        Column(Modifier.padding(start = 32.dp)) {
            if (
                descriptor != null && capabilities.isNotEmpty() &&
                (!entry.enabled || capabilities.any { it.effect != Effect.READ && it.name !in entry.mutations })
            ) {
                TextButton(
                    onClick = {
                        actions.onExtensionEnableAll(entry.key)
                        repositoryInstallation?.let { actions.onExtensionAutoEnable(it.definition.id, true) }
                    },
                    enabled = installed.problem == null,
                ) { Text("Enable all actions") }
            }
            capabilities.forEach { capability ->
                val capabilityId = "${installed.capabilityPrefix}.${capability.name}"
                val unavailable = state.extensionOverflow[capabilityId]
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
                if (capability.effect != Effect.READ || capability.endsVoiceCall != CallEnding.NEVER || capabilityId in state.callEndings) {
                    Row(Modifier.padding(start = 8.dp)) {
                        CallEndingPicker(capability.endsVoiceCall, state.callEndings[capabilityId]) {
                            actions.onCallEnding(capabilityId, it)
                        }
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
                val packageDefinitionId = repositoryInstallation.definition.id
                SettingsRow(
                    "Enable automatically",
                    "Enable actions in new extensions and newly added actions in updates from this repository. " +
                        "Actions you switched off stay off.",
                ) {
                    Switch(
                        checked = state.autoEnabled[packageDefinitionId] ?: true,
                        onCheckedChange = { actions.onExtensionAutoEnable(packageDefinitionId, it) },
                    )
                }
                TextButton(
                    onClick = { actions.onPluginRemove(repositoryInstallation.identity.id) },
                    enabled = !state.plugins.busy,
                ) { Text("Remove extension") }
            }
            folded.forEach { ExtensionEntry(it, emptyList(), state, actions) }
        }
    }
}
