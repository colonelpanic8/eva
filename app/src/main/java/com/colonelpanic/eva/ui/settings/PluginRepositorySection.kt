package com.colonelpanic.eva.ui.settings

import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.colonelpanic.eva.adapters.declarative.PluginBrowserState

@Composable
internal fun PluginRepositorySection(
    state: PluginBrowserState,
    actions: SettingsActions,
) {
    var source by remember(state.source) { mutableStateOf(state.source) }
    SettingsSection("Plugin repository") {
        SettingsBlock {
            Text(
                "Add capabilities from JSON plugin files without updating EVA or the target app. Review a plugin before installing; installation grants no actions.",
            )
            OutlinedTextField(
                value = source,
                onValueChange = { source = it },
                label = { Text("HTTPS index or package URL") },
                singleLine = true,
            )
            Button(onClick = { actions.onRepositoryRefresh(source) }, enabled = !state.busy) { Text("Refresh repository") }
            TextButton(onClick = { actions.onPluginUrlPreview(source) }, enabled = !state.busy) { Text("Preview a package URL") }
            if (state.busy) Text("Loading…")
            state.error?.let { Text(it) }
            state.notice?.let { Text(it) }
            Text("App matching happens on this phone. Apps Android does not reveal may still work with a manually selected plugin.")
        }
        for (listing in state.listings.sortedByDescending { it.androidPackages.any(state.visibleApps::contains) }) {
            val installed = state.installed.find { it.source == state.source && it.definition.id == listing.id }
            val match =
                when {
                    listing.androidPackages.isEmpty() -> "No Android app required"
                    listing.androidPackages.any(state.visibleApps::contains) -> "For an app on this phone"
                    else -> "Target app not detected"
                }
            SettingsRow(listing.title, "$match · version ${listing.version}\n${listing.androidPackages.joinToString()}") {
                TextButton(onClick = { actions.onPluginPreview(listing.id) }, enabled = !state.busy) {
                    Text(if (installed != null && installed.definition.version != listing.version) "Review update" else "Preview")
                }
            }
        }
        state.preview?.let { preview ->
            SettingsBlock {
                Text("Review ${preview.definition.title} ${preview.definition.version}")
                Text("Source: ${preview.url}")
                Text("Targets: ${preview.definition.androidPackages.joinToString().ifEmpty { "See destinations below" }}")
                preview.definition.capabilities.forEach { capability ->
                    Text("${capability.title} · ${capability.effect.name.lowercase()}")
                    Text(capability.description)
                    Text("Destination/binding: ${bindingDestination(capability.binding)}")
                }
                Text(
                    "Plugin text is supplied by its author. Reads disclose returned data to your configured model. Updates with changed content require enabling actions again.",
                )
                Button(onClick = actions.onPluginInstall, enabled = !state.busy) { Text("Install reviewed plugin") }
            }
        }
    }
    if (state.installed.isNotEmpty()) {
        SettingsSection("Repository installations") {
            state.installed.forEach { installed ->
                SettingsRow(installed.definition.title, "${installed.definition.version}\n${installed.source}") {
                    TextButton(onClick = { actions.onPluginRemove(installed.identity.id) }, enabled = !state.busy) { Text("Remove plugin") }
                }
            }
        }
    }
}

private fun bindingDestination(binding: com.colonelpanic.eva.adapters.declarative.DeclarativeBinding): String =
    when (binding) {
        is com.colonelpanic.eva.adapters.declarative.DeclarativeBinding.Intent -> {
            "${binding.action} · ${binding.targetPackage ?: "Android handler"}${binding.targetClass?.let {
                "/$it"
            }.orEmpty()} · ${binding.uriBase}"
        }

        is com.colonelpanic.eva.adapters.declarative.DeclarativeBinding.Http -> {
            "${binding.method} ${binding.origin}${binding.path}"
        }

        is com.colonelpanic.eva.adapters.declarative.DeclarativeBinding.Content -> {
            binding.uri
        }

        is com.colonelpanic.eva.adapters.declarative.DeclarativeBinding.Select -> {
            "${bindingDestination(binding.present)} or ${bindingDestination(binding.absent)}"
        }
    }
