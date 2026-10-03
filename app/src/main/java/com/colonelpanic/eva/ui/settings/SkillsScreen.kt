package com.colonelpanic.eva.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.data.InstalledSkill
import com.colonelpanic.eva.data.configuration.PortableSkill
import com.colonelpanic.eva.skills.Skill
import com.colonelpanic.eva.ui.MenuButton
import com.colonelpanic.eva.ui.evaTopAppBarColors
import com.colonelpanic.eva.ui.theme.EvaTheme

/**
 * Skills in the Codex format: a `SKILL.md` and optional `agents/openai.yaml`. The model sees each
 * enabled skill's name and description and loads its instructions when a request calls for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onOpenDrawer: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Skills") },
                navigationIcon = { MenuButton(onOpenDrawer) },
                colors = evaTopAppBarColors(),
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection("Installed") {
                if (state.skills.isEmpty()) {
                    SettingsRow(
                        title = "No skills yet",
                        supporting = "A skill is a SKILL.md file of instructions for a kind of request, as in Codex. Add one below.",
                    )
                }
                state.skills.forEach { SkillRow(it, actions) }
            }
            SettingsDivider()
            AddSkillSection(state, actions)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SkillRow(
    installed: InstalledSkill,
    actions: SettingsActions,
) {
    val skill = installed.skill
    SettingsRow(
        title = skill.title,
        supporting =
            listOfNotNull(
                skill.shortDescription ?: skill.description,
                skill.name.takeIf { it != skill.title },
                "Only when you name it".takeIf { !skill.implicit },
                skill.dependencies
                    .takeIf { it.isNotEmpty() }
                    ?.let { "Expects tools EVA cannot provide: ${it.joinToString()}" },
                installed.files.source,
            ).joinToString("\n"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { actions.onSkillRemove(skill.name) }) { Text("Remove") }
            Switch(
                checked = installed.enabled,
                onCheckedChange = { actions.onSkillEnable(skill.name, it) },
                modifier = Modifier.semantics { contentDescription = "Use ${skill.title}" },
            )
        }
    }
}

@Composable
private fun AddSkillSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    var address by remember { mutableStateOf("") }
    var skill by remember { mutableStateOf("") }
    var openai by remember { mutableStateOf("") }
    SettingsSection("Add a skill") {
        SettingsBlock {
            state.skillImport.message?.let {
                Text(it, color = if (state.skillImport.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("SKILL.md or skill folder address") },
                supportingText = { Text("A raw HTTPS file, or a GitHub page for the file or its folder") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { actions.onSkillFetch(address) }, enabled = address.isNotBlank() && !state.skillImport.busy) {
                Text(if (state.skillImport.busy) "Fetching…" else "Fetch and install")
            }
            OutlinedTextField(
                value = skill,
                onValueChange = { skill = it },
                label = { Text("SKILL.md") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = openai,
                onValueChange = { openai = it },
                label = { Text("agents/openai.yaml (optional)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    if (actions.onSkillPaste(skill, openai)) {
                        skill = ""
                        openai = ""
                    }
                },
                enabled = skill.isNotBlank(),
            ) { Text("Install pasted skill") }
        }
    }
}

@Preview(name = "Skills", showBackground = true)
@Preview(name = "Skills dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SkillsPreview() {
    val text = "---\nname: daily-planning\ndescription: Use when the user asks to plan their day.\n---\nList today's agenda first."
    EvaTheme(dynamicColor = false) {
        SkillsScreen(
            state = SettingsUiState(skills = listOf(InstalledSkill(PortableSkill(text), Skill.parse(text), enabled = true))),
            actions = SettingsActions(),
            onOpenDrawer = {},
        )
    }
}
