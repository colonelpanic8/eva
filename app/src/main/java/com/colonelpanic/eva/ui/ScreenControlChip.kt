package com.colonelpanic.eva.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.devicecontrol.ScreenControlStatus
import com.colonelpanic.eva.devicecontrol.ScreenControlStatus.Health
import kotlinx.coroutines.delay

/**
 * Always on the conversation bar: a broken screen route otherwise stays invisible until a task
 * fails. Tapping a route that is not ready repairs it; tapping a ready one opens settings.
 */
@Composable
internal fun ScreenControlChip(
    status: ScreenControlStatus,
    taskRunning: Boolean,
    repairing: Set<String>,
    onOpen: () -> Unit,
    onRepair: (ScreenControlStatus.Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now by wallClock()
    if (!status.enabled) {
        AssistChip(
            onClick = onOpen,
            label = { Text("Screen off") },
            modifier =
                modifier.padding(end = 8.dp).clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = "Screen control is off. Open settings."
                },
        )
        return
    }
    Surface(
        shape = AssistChipDefaults.shape,
        border = AssistChipDefaults.assistChipBorder(enabled = true),
        modifier = modifier.padding(end = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            status.routes.forEachIndexed { index, route ->
                val busy = route.backend in repairing || (taskRunning && index == status.preferred)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .clickable(enabled = route.backend !in repairing) {
                                if (route.health == Health.READY) onOpen() else onRepair(route)
                            }.clearAndSetSemantics {
                                role = Role.Button
                                contentDescription = routeDescription(route, busy, route.backend in repairing, now)
                            }.heightIn(min = 32.dp)
                            .padding(horizontal = 8.dp),
                ) {
                    when {
                        busy -> {
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
                    Text(
                        route.sinceMillis?.let { "${route.name} · ${ago(it, now)}" } ?: route.name,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

internal fun routeDescription(
    route: ScreenControlStatus.Route,
    busy: Boolean,
    repairing: Boolean,
    nowMillis: Long,
): String {
    val action =
        when {
            repairing -> "Reconnecting."
            route.health == Health.READY -> "Open settings."
            else -> "Tap to reconnect."
        }
    return "${route.name}: ${routeSummary(route, nowMillis)} ${if (busy && !repairing) "A device task is running. " else ""}$action"
}

/** What a route's health means in words, with the reason when it is not ready. */
internal fun routeSummary(
    route: ScreenControlStatus.Route,
    nowMillis: Long,
): String {
    val since = route.sinceMillis?.let { " · ${ago(it, nowMillis)}" }.orEmpty()
    return when (route.health) {
        Health.READY -> "ready."
        Health.SETUP_NEEDED -> "needs setup. ${route.problem}"
        Health.DEGRADED -> "its check is failing$since. ${route.problem}"
        Health.UNHEALTHY -> "the last screen action through it failed$since. ${route.problem}"
    }
}

/** How long ago [thenMillis] was, coarse enough to read at a glance. */
internal fun ago(
    thenMillis: Long,
    nowMillis: Long,
): String {
    val minutes = (nowMillis - thenMillis).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}

/** The current time, ticking so relative times stay true while the screen is open. */
@Composable
internal fun wallClock(): State<Long> =
    produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
