package com.colonelpanic.eva.ui.prompt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.conversation.prompt.Applies
import com.colonelpanic.eva.conversation.prompt.PromptComponent
import com.colonelpanic.eva.conversation.prompt.PromptConfig
import com.colonelpanic.eva.conversation.prompt.PromptDefaults
import com.colonelpanic.eva.data.PromptLocation
import com.colonelpanic.eva.data.PromptState
import com.colonelpanic.eva.ui.MenuButton
import com.colonelpanic.eva.ui.evaTopAppBarColors
import com.colonelpanic.eva.ui.settings.SettingsBlock
import com.colonelpanic.eva.ui.settings.SettingsDivider
import com.colonelpanic.eva.ui.settings.SettingsRow
import com.colonelpanic.eva.ui.settings.SettingsSection
import com.colonelpanic.eva.ui.theme.EvaTheme

data class PromptUiState(
    val location: PromptLocation = PromptLocation("", chosen = false),
    val prompt: PromptState = PromptState.Loading,
    val notice: String? = null,
)

data class PromptActions(
    val onToggle: (String, Boolean) -> Unit = { _, _ -> },
    val onSave: (PromptComponent) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onOpenFile: () -> Unit = {},
    val onCreateFile: () -> Unit = {},
    val onUseOwnFile: () -> Unit = {},
    val onReset: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

/** Sentinel for the editor: no component has an empty id, so it can only mean a new one. */
private const val NEW_COMPONENT = ""

/**
 * The prompt file, as a screen. Everything here is a view of that file: the switches are its
 * `enabled` fields, the editor writes one entry, and the file section is where it lives. What
 * the screen cannot edit, slots and tool overrides, is said so and left to the file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptScreen(
    state: PromptUiState,
    actions: PromptActions,
    onOpenDrawer: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val config = (state.prompt as? PromptState.Loaded)?.config
    val target = editing
    if (target != null && config != null) {
        BackHandler { editing = null }
        ComponentEditor(
            config = config,
            existing = config.components.firstOrNull { it.id == target },
            onSave = {
                actions.onSave(it)
                editing = null
            },
            onDelete = {
                actions.onDelete(it)
                editing = null
            },
            onBack = { editing = null },
        )
        return
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Prompt") },
                navigationIcon = { MenuButton(onOpenDrawer) },
                colors = evaTopAppBarColors(),
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState()),
        ) {
            FileSection(state, actions)
            SettingsDivider()
            ComponentsSection(state.prompt, actions, onEdit = { editing = it })
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FileSection(
    state: PromptUiState,
    actions: PromptActions,
) {
    SettingsSection("File") {
        SettingsRow(
            title = state.location.name,
            supporting =
                if (state.location.chosen) {
                    "Your file. EVA reads it whenever a session starts and writes it when you change something here."
                } else {
                    "EVA's own copy. Choose a file to keep the prompt somewhere you can sync, back up, or commit."
                },
        )
        SettingsBlock {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.onOpenFile) { Text("Open a file") }
                OutlinedButton(onClick = actions.onCreateFile) { Text("Create a file") }
                if (state.location.chosen) TextButton(onClick = actions.onUseOwnFile) { Text("Use EVA's copy") }
                TextButton(onClick = actions.onReset) { Text("Reset to defaults") }
            }
            (state.prompt as? PromptState.Failed)?.let {
                Text(
                    text = "The file cannot be used, so no session can start. ${it.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.notice?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = actions.onDismissNotice) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun ComponentsSection(
    prompt: PromptState,
    actions: PromptActions,
    onEdit: (String) -> Unit,
) {
    SettingsSection("Components") {
        SettingsBlock {
            Text(
                text =
                    "Enabled components are joined in this order to make the system prompt. Tap one to edit it. " +
                        "Slots, which allow one of their components on at a time, and tool overrides are edited in the file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (prompt) {
            PromptState.Loading -> {
                SettingsBlock { Text("Reading the prompt file…", style = MaterialTheme.typography.bodyMedium) }
            }

            is PromptState.Failed -> {}

            is PromptState.Loaded -> {
                prompt.config.components.forEach { component ->
                    SettingsRow(
                        title = component.title,
                        supporting = componentSupporting(component),
                        onClick = { onEdit(component.id) },
                    ) {
                        Switch(
                            checked = component.enabled,
                            onCheckedChange = { actions.onToggle(component.id, it) },
                            modifier = Modifier.semantics { contentDescription = component.title },
                        )
                    }
                }
                SettingsBlock { OutlinedButton(onClick = { onEdit(NEW_COMPONENT) }) { Text("Add a component") } }
            }
        }
    }
}

internal fun componentSupporting(component: PromptComponent): String =
    listOfNotNull(
        component.summary.takeIf { it.isNotBlank() },
        when (component.applies) {
            Applies.VOICE -> "Voice"
            Applies.TEXT -> "Text"
            Applies.BOTH -> "Voice and text"
        },
        component.slot?.let { "Slot “$it”" },
    ).joinToString(" · ")

/**
 * One component's editable fields. Checked against the whole file before saving, so a
 * duplicate id or an unknown variable is pointed out here rather than after the write.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComponentEditor(
    config: PromptConfig,
    existing: PromptComponent?,
    onSave: (PromptComponent) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    var id by rememberSaveable { mutableStateOf(existing?.id.orEmpty()) }
    var title by rememberSaveable { mutableStateOf(existing?.title.orEmpty()) }
    var summary by rememberSaveable { mutableStateOf(existing?.summary.orEmpty()) }
    var instruction by rememberSaveable { mutableStateOf(existing?.instruction.orEmpty()) }
    var applies by rememberSaveable { mutableStateOf(existing?.applies ?: Applies.BOTH) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun draft() =
        (existing ?: PromptComponent(id = id.trim())).copy(
            id = id.trim(),
            title = title.trim().ifEmpty { id.trim() },
            summary = summary.trim(),
            instruction = instruction.trim(),
            applies = applies,
        )

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New component" else existing.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = evaTopAppBarColors(),
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (existing == null) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("Id") },
                    supportingText = { Text("Lowercase letters, digits, dashes, and slashes; it names the entry in the file.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Id" },
                )
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Title" },
            )
            OutlinedTextField(
                value = summary,
                onValueChange = { summary = it },
                label = { Text("Summary") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Summary" },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppliesChip("Voice and text", Applies.BOTH, applies) { applies = it }
                AppliesChip("Voice only", Applies.VOICE, applies) { applies = it }
                AppliesChip("Text only", Applies.TEXT, applies) { applies = it }
            }
            OutlinedTextField(
                value = instruction,
                onValueChange = { instruction = it },
                label = { Text("Instruction") },
                supportingText = {
                    Text(
                        "Line breaks join into one paragraph; leave a blank line to start another. " +
                            "Variables: ${PromptDefaults.VARIABLES.sorted().joinToString { "{{$it}}" }}.",
                    )
                },
                minLines = 6,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Instruction" },
            )
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val component = draft()
                        val candidate =
                            if (existing == null &&
                                config.components.any { it.id == component.id }
                            ) {
                                null
                            } else {
                                config.upsert(component)
                            }
                        problem =
                            candidate?.problem(PromptDefaults.VARIABLES) ?: "A component with the id “${component.id}” already exists."
                        if (candidate != null && problem == null) onSave(component)
                    },
                ) { Text("Save") }
                if (existing != null) TextButton(onClick = { onDelete(existing.id) }) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun AppliesChip(
    label: String,
    value: Applies,
    selected: Applies,
    onSelect: (Applies) -> Unit,
) {
    FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
}

@Preview(name = "Prompt", showBackground = true)
@Preview(name = "Prompt dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PromptPreview() {
    EvaTheme(dynamicColor = false) {
        PromptScreen(
            state =
                PromptUiState(
                    location =
                        PromptLocation(
                            "/storage/emulated/0/Android/data/com.colonelpanic.eva/files/eva-prompt.yaml",
                            chosen = false,
                        ),
                    prompt = PromptState.Loaded(PromptDefaults.config),
                ),
            actions = PromptActions(),
            onOpenDrawer = {},
        )
    }
}

@Preview(name = "Prompt broken", showBackground = true)
@Composable
private fun PromptBrokenPreview() {
    EvaTheme(dynamicColor = false) {
        PromptScreen(
            state =
                PromptUiState(
                    location = PromptLocation("eva-prompt.yaml", chosen = true),
                    prompt = PromptState.Failed("Line 12, column 3: Unknown property 'instructions'. Known properties are: instruction, …"),
                ),
            actions = PromptActions(),
            onOpenDrawer = {},
        )
    }
}
