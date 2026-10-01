package com.colonelpanic.eva.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.devicecontrol.ScreenControlStatus
import com.colonelpanic.eva.devicecontrol.ScreenControlStatus.Health

/** Always on the conversation bar: a broken screen route otherwise stays invisible until a task fails. */
@Composable
internal fun ScreenControlChip(
    status: ScreenControlStatus,
    taskRunning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = onClick,
        label = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!status.enabled) Text("Screen off")
                status.routes.forEachIndexed { index, route ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        when {
                            taskRunning && index == status.preferred -> {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            }

                            route.health == Health.READY -> {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }

                            route.health == Health.SETUP_NEEDED -> {
                                Icon(
                                    Icons.Filled.Settings,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }

                            else -> {
                                Icon(
                                    Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        Text(route.name)
                    }
                }
            }
        },
        modifier =
            modifier.padding(end = 8.dp).clearAndSetSemantics {
                role = Role.Button
                contentDescription = screenControlDescription(status, taskRunning)
            },
    )
}

internal fun screenControlDescription(
    status: ScreenControlStatus,
    taskRunning: Boolean,
): String {
    if (!status.enabled) return "Screen control is off. Open settings."
    val routes = status.routes.joinToString(" ") { "${it.name}: ${routeSummary(it)}" }
    return "Screen control. ${if (taskRunning) "A device task is running. " else ""}$routes Open settings."
}

/** What a route's health means in words, with the reason when it is not ready. */
internal fun routeSummary(route: ScreenControlStatus.Route): String =
    when (route.health) {
        Health.READY -> "ready."
        Health.SETUP_NEEDED -> "needs setup. ${route.problem}"
        Health.DEGRADED -> "its check is failing. ${route.problem}"
        Health.UNHEALTHY -> "the last screen action through it failed. ${route.problem}"
    }
