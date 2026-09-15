package com.colonelpanic.eva.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction

/** Every model worth listing: the account's, plus the default and the current choice. */
internal fun modelCandidates(
    available: List<String>,
    selected: String,
    defaultModel: String,
): List<String> = (available + listOf(defaultModel, selected).filter { it.isNotBlank() }).distinct().sorted()

/**
 * What the menu shows. The field opens holding the current model, so filtering on its text
 * would hide the whole account list behind the one name already chosen; [editing] says the
 * text is a filter the user typed rather than the standing selection.
 */
internal fun listedModels(
    candidates: List<String>,
    draft: String,
    editing: Boolean,
): List<String> {
    val filter = draft.trim()
    if (!editing || filter.isEmpty()) return candidates
    return candidates.filter { it.contains(filter, ignoreCase = true) }
}

/**
 * A normal editable dropdown for model choice. [available] comes from the account when it
 * can be listed; typing a name not in the list is still accepted so a model missing
 * from the list is reachable, and blank restores [defaultModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPicker(
    label: String,
    selected: String,
    available: List<String>,
    defaultModel: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var draft by remember(selected) { mutableStateOf(selected) }
    var editing by remember(selected) { mutableStateOf(false) }
    val candidates =
        remember(available, selected, defaultModel) {
            modelCandidates(available, selected, defaultModel)
        }
    val filtered =
        remember(draft, candidates, editing) {
            listedModels(candidates, draft, editing)
        }
    val trimmed = draft.trim()

    fun commit(value: String) {
        expanded = false
        editing = false
        draft = value
        onSelect(value.trim())
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                editing = true
                expanded = true
            },
            label = { Text(label) },
            placeholder = { Text(defaultModel) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        if (trimmed.isEmpty()) commit("") else commit(trimmed)
                    },
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                    .semantics { contentDescription = label },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Default ($defaultModel)") },
                onClick = { commit("") },
            )
            if (available.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No models listed for this account yet") },
                    onClick = {},
                    enabled = false,
                )
            }
            filtered.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model) },
                    onClick = { commit(model) },
                )
            }
            if (trimmed.isNotEmpty() && trimmed !in candidates) {
                DropdownMenuItem(
                    text = { Text("Use \"$trimmed\"") },
                    onClick = { commit(trimmed) },
                )
            }
        }
    }
}

/**
 * A closed choice for a leg's `reasoning.effort` level. Unlike [ModelPicker] this offers no
 * free text: only a value the provider accepts can be stored. The text and speech legs accept
 * different sets, so each supplies its own [label] and [available] values.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReasoningEffortPicker(
    label: String,
    selected: String,
    available: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    fun commit(value: String) {
        expanded = false
        onSelect(value)
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .semantics { contentDescription = label },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            available.forEach { effort ->
                DropdownMenuItem(
                    text = { Text(effort) },
                    onClick = { commit(effort) },
                )
            }
        }
    }
}
