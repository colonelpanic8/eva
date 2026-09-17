package com.colonelpanic.eva.ui.settings

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.colonelpanic.eva.adapters.declarative.PluginBrowserState

/**
 * The first thing the extensions screen shows, because an update nobody notices is an
 * extension that stays stale. It states what is available and what accepting it costs;
 * installing still goes through the same review every other install does.
 */
@Composable
internal fun ExtensionUpdatesSection(
    state: PluginBrowserState,
    actions: SettingsActions,
) {
    val updates = state.updates
    SettingsSection(if (updates.isEmpty()) "Extension updates" else "Extension updates (${updates.size})") {
        SettingsBlock {
            Text(
                when {
                    updates.isNotEmpty() -> {
                        "Your catalog has a newer version of ${
                            if (updates.size == 1) "one installed extension" else "${updates.size} installed extensions"
                        }. Review one to see what changed before it replaces what you approved."
                    }

                    state.checked -> {
                        "Every installed extension matches the catalog EVA listed."
                    }

                    else -> {
                        "EVA lists your catalog when this screen opens, and shows any newer versions here."
                    }
                },
            )
            if (updates.isNotEmpty()) {
                Text("An updated extension is a new contract, so its actions must be enabled again and the conversation reconnected.")
            }
            if (state.busy) Text("Checking the catalog…")
            state.error?.let { Text(it) }
            TextButton(onClick = actions.onRepositoryRefresh, enabled = !state.busy) { Text("Check for updates") }
        }
        for (update in updates) {
            SettingsRow(
                update.title,
                "Installed ${update.installedVersion} · available ${update.availableVersion}",
            ) {
                Button(onClick = { actions.onPluginPreview(update.id) }, enabled = !state.busy) { Text("Review update") }
            }
        }
        state.reviewedUpdate()?.let { update ->
            state.preview?.let { ExtensionPreviewBlock(it, state, actions, update.installedVersion) }
        }
    }
}
