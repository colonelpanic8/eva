package com.colonelpanic.eva.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.adapters.declarative.DeclarativeBinding
import com.colonelpanic.eva.adapters.declarative.PluginBrowserState
import com.colonelpanic.eva.adapters.declarative.appTargets
import com.colonelpanic.eva.adapters.declarative.toEffect

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
    SettingsSection("Repositories") {
        SettingsBlock {
            SettingsNote("EVA installs and updates everything a followed repository publishes. Follow only repositories you trust.")
            if (state.busy) SettingsNote("Refreshing…")
            state.error?.let { SettingsNote(it, error = true) }
            state.notice?.let { SettingsNote(it) }
        }
        for (repository in state.repositories) {
            val installedHere = state.installed.count { it.source == repository.source }
            SettingsRow(
                repository.source.substringAfterLast('/').removeSuffix(".git"),
                listOfNotNull(
                    repository.source.removePrefix("https://"),
                    when {
                        repository.busy -> "Refreshing…"
                        repository.error != null -> repository.error
                        repository.refreshed -> "${repository.listings.size} published · $installedHere installed"
                        else -> "Not refreshed yet"
                    },
                    repository.problems.takeIf { it.isNotEmpty() }?.joinToString("\n") { "Skipped $it" },
                ).joinToString("\n"),
                supportingIsError = repository.error != null,
            ) {
                Row {
                    IconButton(onClick = { actions.onRepositorySync(repository.source) }, enabled = !state.busy) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh ${repository.source}")
                    }
                    IconButton(onClick = { actions.onRepositoryRemove(repository.source) }, enabled = !state.busy) {
                        Icon(Icons.Default.Delete, contentDescription = "Unfollow ${repository.source}")
                    }
                }
            }
        }
        SettingsBlock {
            OutlinedTextField(
                value = repositoryUrl,
                onValueChange = { repositoryUrl = it },
                label = { Text("Repository URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        actions.onRepositoryAdd(repositoryUrl)
                        repositoryUrl = ""
                    },
                    enabled = !state.busy && repositoryUrl.isNotBlank(),
                ) { Text("Follow") }
                if (state.repositories.isNotEmpty()) {
                    TextButton(onClick = actions.onRepositoryRefresh, enabled = !state.busy) { Text("Refresh all") }
                }
            }
        }
    }
    SettingsDivider()
    ExtensionImportSection(state, actions)
}

/** One-off packages that belong to no catalog, so nothing refreshes them. These are still reviewed. */
@Composable
private fun ExtensionImportSection(
    state: PluginBrowserState,
    actions: SettingsActions,
) {
    var packageUrl by remember { mutableStateOf("") }
    SettingsSection("Single extension") {
        SettingsBlock {
            SettingsNote("Imported from a file or URL, outside any repository, so it never updates. You review it before it installs.")
            OutlinedTextField(
                value = packageUrl,
                onValueChange = { packageUrl = it },
                label = { Text("Package URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.onPluginUrlPreview(packageUrl) }, enabled = !state.busy && packageUrl.isNotBlank()) {
                    Text("Preview")
                }
                TextButton(onClick = actions.onPluginFileImport, enabled = !state.busy) { Text("Choose a file") }
            }
        }
        state.preview?.let { preview ->
            SettingsPanel(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "${preview.definition.title} ${preview.definition.version}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        when {
                            preview.source.startsWith("file-import:") -> "Selected file"
                            preview.url == preview.source -> preview.url
                            else -> "${preview.source} · ${preview.url}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    preview.definition.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    val reaches =
                        (preview.definition.appTargets() + preview.definition.capabilities.flatMap { endpoints(it.binding) }).distinct()
                    if (reaches.isNotEmpty()) {
                        Text("Reaches ${reaches.joinToString()}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (preview.definition.setup.isNotEmpty()) {
                        Text("Before it works", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        preview.definition.setup.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                preview.definition.capabilities.forEach { capability ->
                    ActionItem(
                        title = capability.title,
                        description = capability.description,
                        effect = capability.effect.toEffect(),
                        inputSchema = capability.inputSchema,
                        outputSchema = capability.outputSchema,
                    ) {
                        Text(
                            bindingDestination(capability.binding),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Descriptions come from its author. What it reads may be shared with your configured model.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = actions.onPluginInstall, enabled = !state.busy) { Text("Install") }
                }
            }
        }
    }
}

/** Servers and content providers; the apps an intent targets come from [appTargets]. */
private fun endpoints(binding: DeclarativeBinding): List<String> =
    when (binding) {
        is DeclarativeBinding.Http -> listOf(binding.origin)
        is DeclarativeBinding.Content -> listOf(binding.uri.substringAfter("://").substringBefore('/'))
        is DeclarativeBinding.Select -> endpoints(binding.present) + endpoints(binding.absent)
        is DeclarativeBinding.Intent -> emptyList()
    }

private fun bindingDestination(binding: DeclarativeBinding): String =
    when (binding) {
        is DeclarativeBinding.Intent -> {
            val action = binding.action ?: "one of ${binding.actionSlot?.values?.values?.sorted()?.joinToString()}"
            val destination =
                binding.uriArgument?.let { "a ${binding.uriSchemes.joinToString("/")} URL from the request" } ?: binding.uriBase
            "$action · ${binding.targetPackage ?: "Android handler"}${binding.targetClass?.let {
                "/$it"
            }.orEmpty()} · $destination"
        }

        is DeclarativeBinding.Http -> {
            "${binding.method} ${binding.origin}${binding.path}"
        }

        is DeclarativeBinding.Content -> {
            binding.uri
        }

        is DeclarativeBinding.Select -> {
            "${bindingDestination(binding.present)} or ${bindingDestination(binding.absent)}"
        }
    }
