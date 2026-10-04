package com.colonelpanic.eva.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.capability.CallEnding
import com.colonelpanic.eva.capability.CatalogAdmission
import com.colonelpanic.eva.capability.extensions.Effect
import com.colonelpanic.eva.capability.extensions.ExtensionSettingsEntry
import kotlinx.serialization.json.JsonObject

@Composable
internal fun ExtensionsSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val extensions = state.extensions
    Column(Modifier.padding(top = 16.dp)) {
        SettingsBlock {
            state.catalogAdmission?.let { admission ->
                SettingsNote("Offering ${admission.voice.admitted.size} tools by voice and ${admission.text.admitted.size} in text.")
            }
            SettingsNote(
                "Each app declares what its actions do; EVA can't verify it. What an action reads goes to your model. " +
                    "Changes apply from the next connection.",
            )
            extensions.error?.let { SettingsNote(it, error = true) }
        }
        state.catalogAdmission?.let { CatalogOverflow(it, state.extensionOverflow) }
        if (extensions.entries.isEmpty()) {
            SettingsRow("No extensions yet", "Install an app that provides one, or follow a repository under Sources.")
        }
        val folded = extensions.entries.filter { it.supersession != null }.groupBy { it.supersession?.owner }
        for (entry in extensions.entries.filter { it.supersession == null }) {
            ExtensionEntry(entry, folded[entry.installed.identity?.instanceId].orEmpty(), state, actions)
        }
    }
}

@Composable
private fun CatalogOverflow(
    admission: CatalogAdmission.Preview,
    reasons: Map<String, String>,
) {
    if (reasons.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${reasons.size} enabled actions aren't offered to the model", style = MaterialTheme.typography.titleSmall)
            Text(
                "A session holds at most ${CatalogAdmission.LIMIT} tools, and voice reserves a few for call controls. " +
                    "EVA's own actions and installed apps come first; other extensions follow whole, in a stable order.",
                style = MaterialTheme.typography.bodySmall,
            )
            (admission.text.overflow + admission.voice.overflow).distinctBy { it.id }.forEach { tool ->
                Text("${tool.title} · ${shortReason(reasons.getValue(tool.id))}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** The overflow reason's first sentence says which modes miss the action; the rest is shared policy. */
private fun shortReason(reason: String) = reason.substringBefore(". ").removeSuffix(".")

/** One extension; a package the app's own extension speaks for is folded inside it, showing only what remains. */
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
    val title = descriptor?.title?.let { if (entry.supersession != null) "More $it actions" else it } ?: installed.packageName
    val enabledCount = if (entry.enabled) capabilities.count { it.effect == Effect.READ || it.name in entry.mutations } else 0
    var expanded by rememberSaveable(entry.key) { mutableStateOf(false) }
    ExpandableSettingsRow(
        title = title,
        supporting =
            installed.problem ?: when {
                !entry.enabled -> "Off · ${plural(capabilities.size, "action")}"
                else -> "$enabledCount of ${plural(capabilities.size, "action")} on"
            },
        supportingIsError = installed.problem != null,
        expanded = expanded,
        onExpandedChange = { expanded = it },
        leading = { InstalledAppIcon(installed.androidPackages) { LetterAvatar(title) } },
        trailing = {
            Switch(
                checked = entry.enabled,
                enabled = descriptor != null && (entry.enabled || installed.problem == null),
                onCheckedChange = { value ->
                    actions.onExtensionEnable(entry.key, value)
                    // Turning an extension off also tells the next catalog refresh to leave it off.
                    repositoryInstallation?.let { actions.onExtensionAutoEnable(it.definition.id, value) }
                },
                modifier = Modifier.semantics { contentDescription = "Use $title" },
            )
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                installed.packageName,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
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
                ) { Text("Turn on all") }
            }
        }
        capabilities.forEach { capability ->
            val capabilityId = "${installed.capabilityPrefix}.${capability.name}"
            val callEnding = state.callEndings[capabilityId] ?: capability.endsVoiceCall
            ActionItem(
                title = capability.title,
                description = capability.description,
                effect = capability.effect,
                inputSchema = capability.inputSchema,
                outputSchema = capability.outputSchema,
                unavailable = state.extensionOverflow[capabilityId]?.let(::shortReason),
                endsCall = callEnding != CallEnding.NEVER,
                toggle =
                    if (capability.effect == Effect.READ) {
                        null
                    } else {
                        {
                            Switch(
                                checked = capability.name in entry.mutations,
                                enabled = entry.enabled && (installed.problem == null || capability.name in entry.mutations),
                                onCheckedChange = { actions.onExtensionMutation(entry.key, capability.name, it) },
                                modifier = Modifier.semantics { contentDescription = "Allow ${capability.title}" },
                            )
                        }
                    },
            ) {
                if (capability.effect != Effect.READ || capability.endsVoiceCall != CallEnding.NEVER || capabilityId in state.callEndings) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("After it succeeds in a call", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        CallEndingPicker(capability.endsVoiceCall, state.callEndings[capabilityId]) {
                            actions.onCallEnding(capabilityId, it)
                        }
                    }
                }
            }
        }
        val authorities = configurations.flatMap { it.contentAuthorities }.distinct().filter { it in state.contentProviders }
        if (authorities.isNotEmpty() || configurations.isNotEmpty() || repositoryInstallation != null) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        authorities.forEach { authority ->
            val access = state.contentProviders.getValue(authority)
            SettingsRow(
                title = "Read $authority",
                supporting =
                    listOfNotNull(
                        access.problem ?: "Android access available; the app may still refuse EVA.",
                        "Reads can share this app's data with your model. Granted per device.".takeIf { access.canRequest },
                    ).joinToString("\n"),
            ) {
                when {
                    access.canRequest -> TextButton(onClick = { actions.onContentPermission(authority) }) { Text("Allow") }
                    access.permission != null -> TextButton(onClick = actions.onOpenAppSettings) { Text("Settings") }
                }
            }
        }
        configurations.forEachIndexed { index, configuration ->
            ExtensionConfiguration(configuration, actions, showWait = index == configurations.lastIndex)
        }
        if (repositoryInstallation != null) {
            val packageDefinitionId = repositoryInstallation.definition.id
            SettingsRow(
                "Turn on new actions",
                "When the repository adds actions. Ones you turned off stay off.",
            ) {
                Switch(
                    checked = state.autoEnabled[packageDefinitionId] ?: true,
                    onCheckedChange = { actions.onExtensionAutoEnable(packageDefinitionId, it) },
                    modifier = Modifier.semantics { contentDescription = "Turn on new $title actions" },
                )
            }
            OutlinedButton(
                onClick = { actions.onPluginRemove(repositoryInstallation.identity.id) },
                enabled = !state.plugins.busy,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) { Text("Remove extension") }
        }
        folded.forEach { ExtensionEntry(it, emptyList(), state, actions) }
    }
}

/**
 * One action: what it does at a glance, and on tap the types it takes and returns. [details]
 * holds per-action choices such as whether a call ends after it.
 */
@Composable
internal fun ActionItem(
    title: String,
    description: String,
    effect: Effect,
    inputSchema: JsonObject,
    outputSchema: JsonObject?,
    unavailable: String? = null,
    endsCall: Boolean = false,
    toggle: (@Composable () -> Unit)? = null,
    details: @Composable () -> Unit = {},
) {
    var expanded by rememberSaveable(title, inputSchema.hashCode()) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val parameters = schemaFields(inputSchema).size
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (expanded) "Hide details" else "Show parameters and details") { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    EffectLabel(effect)
                    if (endsCall) SettingsLabel("Ends call", colors.surfaceContainerHighest, colors.onSurfaceVariant)
                    Text(
                        if (parameters == 0) "No parameters" else plural(parameters, "parameter"),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            ExpandIcon(expanded, null, Modifier.padding(start = 8.dp))
            if (toggle != null) {
                Spacer(Modifier.width(8.dp))
                toggle()
            }
        }
        unavailable?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionSignature(inputSchema, outputSchema)
                details()
            }
        }
    }
}

@Composable
private fun EffectLabel(effect: Effect) {
    val colors = MaterialTheme.colorScheme
    when (effect) {
        Effect.READ -> SettingsLabel("Reads", colors.surfaceContainerHighest, colors.onSurfaceVariant)
        Effect.WRITE -> SettingsLabel("Changes data")
        Effect.HANDOFF -> SettingsLabel("Hands off", colors.primaryContainer, colors.onPrimaryContainer)
        Effect.UNKNOWN -> SettingsLabel("Unknown effect", colors.errorContainer, colors.onErrorContainer)
    }
}

internal fun plural(
    count: Int,
    noun: String,
) = "$count $noun" + if (count == 1) "" else "s"
