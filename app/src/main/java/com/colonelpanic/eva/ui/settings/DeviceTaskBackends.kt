package com.colonelpanic.eva.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.data.configuration.DeviceTaskConfiguration

/** Enabled backends first, in preference order, then the ones left out. */
internal fun backendRows(backends: List<String>): List<String> = backends + DeviceTaskConfiguration.BACKENDS.filterNot { it in backends }

/** At least one backend always stays enabled. */
internal fun toggleBackend(
    backends: List<String>,
    backend: String,
): List<String> =
    when {
        backend !in backends -> backends + backend
        backends.size > 1 -> backends - backend
        else -> backends
    }

internal fun moveBackend(
    backends: List<String>,
    backend: String,
    offset: Int,
): List<String> {
    val from = backends.indexOf(backend)
    val to = from + offset
    if (from < 0 || to !in backends.indices) return backends
    return backends.toMutableList().apply { add(to, removeAt(from)) }
}

@Composable
internal fun DeviceTaskBackendList(
    backends: List<String>,
    problems: Map<String, String?>,
    onChange: (List<String>) -> Unit,
) {
    SettingsBlock {
        Text("Device task backends", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Tasks use the first ready backend in this order. One that cannot read the screen hands over to the " +
                "next before any action is taken.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    backendRows(backends).forEach { backend ->
        val label = if (backend == "portal") "Portal" else "Shizuku"
        val enabled = backend in backends
        val position = backends.indexOf(backend)
        val problem = problems[label]
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = enabled,
                onCheckedChange = { onChange(toggleBackend(backends, backend)) },
                enabled = !enabled || backends.size > 1,
                modifier = Modifier.semantics { contentDescription = "Use $label for device tasks" },
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (enabled) "${position + 1}. $label" else label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    when {
                        backend == "portal" -> "Portal app on this phone. "
                        else -> "EVA's own helper through Shizuku. "
                    } + (problem ?: "Ready."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (problem == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            if (enabled) {
                IconButton(onClick = { onChange(moveBackend(backends, backend, -1)) }, enabled = position > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Prefer $label")
                }
                IconButton(onClick = { onChange(moveBackend(backends, backend, 1)) }, enabled = position < backends.lastIndex) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Prefer $label less")
                }
            }
        }
    }
}
