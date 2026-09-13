package com.colonelpanic.eva.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Chooses a model by name. [available] comes from the account when it can be listed;
 * a typed name is always accepted so a model missing from the list is still reachable.
 */
@Composable
internal fun ModelPicker(
    label: String,
    selected: String,
    available: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$label: $selected", style = MaterialTheme.typography.bodySmall)
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.semantics { contentDescription = "Change $label" },
            ) { Text("Change") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                available.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model) },
                        onClick = {
                            expanded = false
                            custom = false
                            onSelect(model)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (available.isEmpty()) "Enter a model name" else "Other…") },
                    onClick = {
                        expanded = false
                        typed = selected
                        custom = true
                    },
                )
                DropdownMenuItem(
                    text = { Text("Use the default") },
                    onClick = {
                        expanded = false
                        custom = false
                        onSelect("")
                    },
                )
            }
        }
        if (custom) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text("$label name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = {
                    custom = false
                    onSelect(typed)
                },
                enabled = typed.isNotBlank(),
            ) { Text("Use this model") }
        }
    }
}
