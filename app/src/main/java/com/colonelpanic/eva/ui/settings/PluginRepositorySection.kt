package com.colonelpanic.eva.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.colonelpanic.eva.adapters.declarative.PluginBrowserState
import com.colonelpanic.eva.adapters.declarative.appTargets

/**
 * The catalogs this phone follows. Refreshing one installs everything it publishes and updates
 * what changed; there is no per-package review, because following a repository is the decision.
 */
@Composable
internal fun ExtensionCatalogSection(
    state: PluginBrowserState,
    actions: SettingsActions,
) {
    var repositoryUrl by remember { mutableStateOf("") }
    SettingsSection("Extension repositories") {
        SettingsBlock {
            Text("Refreshing a repository installs and updates every extension it publishes. Add only repositories you trust.")
            if (state.busy) Text("Refreshing…")
            state.error?.let { Text(it) }
            state.notice?.let { Text(it) }
            TextButton(onClick = actions.onRepositoryRefresh, enabled = !state.busy) { Text("Refresh all repositories") }
        }
        for (repository in state.repositories) {
            val installedHere = state.installed.count { it.source == repository.source }
            SettingsRow(
                repository.source.substringAfterLast('/').removeSuffix(".git"),
                listOfNotNull(
                    repository.source,
                    when {
                        repository.busy -> "Refreshing…"
                        repository.error != null -> repository.error
                        repository.refreshed -> "${repository.listings.size} published · $installedHere installed"
                        else -> "Not refreshed yet"
                    },
                    repository.problems.takeIf { it.isNotEmpty() }?.joinToString("\n") { "Skipped $it" },
                ).joinToString("\n"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { actions.onRepositoryRemove(repository.source) }, enabled = !state.busy) { Text("Stop") }
                    Button(onClick = { actions.onRepositorySync(repository.source) }, enabled = !state.busy) { Text("Refresh") }
                }
            }
        }
        SettingsBlock {
            OutlinedTextField(
                value = repositoryUrl,
                onValueChange = { repositoryUrl = it },
                label = { Text("Repository URL") },
                singleLine = true,
            )
            TextButton(
                onClick = {
                    actions.onRepositoryAdd(repositoryUrl)
                    repositoryUrl = ""
                },
                enabled = !state.busy,
            ) { Text("Follow repository") }
        }
    }
    ExtensionImportSection(state, actions)
}

/** One-off packages that belong to no catalog, so nothing refreshes them. These are still reviewed. */
@Composable
private fun ExtensionImportSection(
    state: PluginBrowserState,
    actions: SettingsActions,
) {
    var packageUrl by remember { mutableStateOf("") }
    SettingsSection("Import a single extension") {
        SettingsBlock {
            Text("An imported file or URL is not part of a repository, so EVA never refreshes or updates it. Review it before installing.")
            OutlinedTextField(
                value = packageUrl,
                onValueChange = { packageUrl = it },
                label = { Text("Extension package URL") },
                singleLine = true,
            )
            TextButton(onClick = { actions.onPluginUrlPreview(packageUrl) }, enabled = !state.busy) { Text("Preview extension URL") }
            TextButton(onClick = actions.onPluginFileImport, enabled = !state.busy) { Text("Import extension file") }
        }
        state.preview?.let { preview ->
            SettingsBlock {
                Text("Review ${preview.definition.title} ${preview.definition.version}")
                Text(
                    "Source: " +
                        when {
                            preview.source.startsWith("file-import:") -> "Selected file"
                            preview.url == preview.source -> preview.url
                            else -> "${preview.source} · ${preview.url}"
                        },
                )
                preview.definition.description?.let { Text(it) }
                Text("Targets: ${preview.definition.androidPackages.joinToString().ifEmpty { "See destinations below" }}")
                if (preview.definition.setup.isNotEmpty()) {
                    Text("Before these actions work:")
                    preview.definition.setup.forEach { Text("• $it") }
                }
                preview.definition.capabilities.forEach { capability ->
                    Text("${capability.title} · ${capability.effect.name.lowercase()}")
                    Text(capability.description)
                    Text("Destination/binding: ${bindingDestination(capability.binding)}")
                }
                Text("Extension text is supplied by its author. Reads disclose returned data to your configured model.")
                Button(onClick = actions.onPluginInstall, enabled = !state.busy) { Text("Install reviewed extension") }
            }
        }
    }
}

private fun bindingDestination(binding: com.colonelpanic.eva.adapters.declarative.DeclarativeBinding): String =
    when (binding) {
        is com.colonelpanic.eva.adapters.declarative.DeclarativeBinding.Intent -> {
            val action = binding.action ?: "one of ${binding.actionSlot?.values?.values?.sorted()?.joinToString()}"
            val destination =
                binding.uriArgument?.let { "a ${binding.uriSchemes.joinToString("/")} URL from the request" } ?: binding.uriBase
            "$action · ${binding.targetPackage ?: "Android handler"}${binding.targetClass?.let {
                "/$it"
            }.orEmpty()} · $destination"
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
