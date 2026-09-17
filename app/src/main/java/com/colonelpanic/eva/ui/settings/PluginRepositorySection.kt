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
import com.colonelpanic.eva.adapters.declarative.PluginPreview
import com.colonelpanic.eva.adapters.declarative.PluginUpdate
import com.colonelpanic.eva.adapters.declarative.appTargets

@Composable
internal fun ExtensionCatalogSection(
    state: PluginBrowserState,
    actions: SettingsActions,
) {
    var packageUrl by remember { mutableStateOf("") }
    SettingsSection("Available extensions") {
        SettingsBlock {
            Text(
                "Review an extension before installing it. Installation does not enable any actions.",
            )
            if (state.busy) Text("Loading…")
            state.error?.let { Text(it) }
            state.notice?.let { Text(it) }
            Text("App matching happens on this phone. Apps Android does not reveal may still work with a manually selected extension.")
            TextButton(onClick = actions.onRepositoryRefresh, enabled = !state.busy) {
                Text("Refresh available extensions")
            }
            OutlinedTextField(
                value = packageUrl,
                onValueChange = { packageUrl = it },
                label = { Text("Extension package URL") },
                singleLine = true,
            )
            TextButton(onClick = { actions.onPluginUrlPreview(packageUrl) }, enabled = !state.busy) {
                Text("Preview extension URL")
            }
            TextButton(onClick = actions.onPluginFileImport, enabled = !state.busy) { Text("Import extension file") }
        }
        for (listing in state.listings.sortedByDescending { it.androidPackages.any(state.visibleApps::contains) }) {
            val match =
                when {
                    listing.androidPackages.isEmpty() -> "No Android app required"
                    listing.androidPackages.any(state.visibleApps::contains) -> "For an app on this phone"
                    else -> "Target app not detected"
                }
            SettingsRow(
                listing.title,
                "$match · version ${listing.version}\n${listing.androidPackages.joinToString()}",
                leading = { InstalledAppIcon(listing.androidPackages) },
            ) {
                TextButton(onClick = { actions.onPluginPreview(listing.id) }, enabled = !state.busy) {
                    Text(if (state.updates.any { it.id == listing.id }) "Review update" else "Preview")
                }
            }
        }
        // An update under review is shown beside its offer at the top of the screen instead.
        if (state.reviewedUpdate() == null) state.preview?.let { ExtensionPreviewBlock(it, state, actions, null) }
    }
}

/** The update the open preview would install, when the preview came from the catalog. */
internal fun PluginBrowserState.reviewedUpdate(): PluginUpdate? =
    preview?.let { open -> updates.find { it.id == open.definition.id && open.source == source } }

/**
 * Everything the user approves before installing: where the bytes came from, what the
 * extension says it is for, and every action with its effect and destination.
 */
@Composable
internal fun ExtensionPreviewBlock(
    preview: PluginPreview,
    state: PluginBrowserState,
    actions: SettingsActions,
    replacing: String?,
) {
    SettingsBlock {
        Text(
            if (replacing == null) {
                "Review ${preview.definition.title} ${preview.definition.version}"
            } else {
                "Review the update from $replacing to ${preview.definition.version} · ${preview.definition.title}"
            },
        )
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
        Text(
            "Extension text is supplied by its author. Reads disclose returned data to your configured model. Updates with changed content require enabling actions again.",
        )
        Button(onClick = actions.onPluginInstall, enabled = !state.busy) {
            Text(if (replacing == null) "Install reviewed extension" else "Install reviewed update")
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
