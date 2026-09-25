package com.colonelpanic.eva.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.colonelpanic.eva.capability.CallEnding

/** Chooses whether a voice call ends once an action succeeds. Choosing the action's own default clears the override. */
@Composable
internal fun CallEndingPicker(
    declared: CallEnding,
    chosen: CallEnding?,
    onChoose: (CallEnding?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Voice call: ${(chosen ?: declared).label()}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CallEnding.entries.forEach { ending ->
                DropdownMenuItem(
                    text = { Text(ending.label() + if (ending == declared) " (default)" else "") },
                    onClick = {
                        open = false
                        onChoose(ending.takeUnless { it == declared })
                    },
                )
            }
        }
    }
}

private fun CallEnding.label() =
    when (this) {
        CallEnding.NEVER -> "stays open"
        CallEnding.AFTER_REPLY -> "ends after EVA confirms"
        CallEnding.IMMEDIATELY -> "ends right away"
    }
