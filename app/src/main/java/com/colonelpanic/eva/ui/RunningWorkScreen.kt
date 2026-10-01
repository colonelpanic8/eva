package com.colonelpanic.eva.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.conversation.TaskSnapshot
import com.colonelpanic.eva.conversation.WorkCoverage
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunningWorkScreen(
    tasks: List<TaskSnapshot>,
    onOpenDrawer: () -> Unit,
    onStop: (String) -> Unit,
    onForceStop: (String) -> Unit,
    onStopAll: () -> Unit,
    onOpenThread: (String) -> Unit,
) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Running work (${tasks.size})") },
            navigationIcon = { MenuButton(onOpenDrawer) },
            actions = { TextButton(onClick = onStopAll, enabled = tasks.isNotEmpty()) { Text("Stop all") } },
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (tasks.isEmpty()) item { Text("No active tasks.") }
            items(tasks, key = { it.taskId }) { task ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(task.threadTitle, style = MaterialTheme.typography.titleMedium)
                        Text(task.request.ifBlank { "Voice request" })
                        Text(
                            "${task.kind.name.label()} · ${if (task.state == com.colonelpanic.eva.conversation.TaskState.RELEASING_DEVICE) {
                                "Releasing device…"
                            } else {
                                task.state.name
                                    .label()
                            }}" +
                                if (task.looksStuck) " · Looks stuck" else "",
                        )
                        Text("Started ${time(task.startedAt)} · last progress ${time(task.lastProgressAt)}")
                        Text("${task.actionCount} actions" + (task.lastActionTitle?.let { " · $it: ${task.lastActionStatus}" } ?: ""))
                        Text(if (task.holdsDeviceLease) "Holds device control" else "Does not hold device control")
                        Text(
                            when (task.coverage) {
                                WorkCoverage.LONG_RUNNING -> "Foreground service active"
                                WorkCoverage.SHORT_SERVICE -> "Limited background time: about three minutes from promotion"
                                WorkCoverage.NONE -> "No task foreground-service coverage"
                            },
                        )
                        Text("Task ${task.taskId}", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { onStop(task.taskId) }) { Text("Stop") }
                            TextButton(onClick = { onForceStop(task.taskId) }) { Text("Force stop") }
                            TextButton(onClick = { onOpenThread(task.threadId) }) { Text("Open thread") }
                        }
                        Text(
                            "Stop drains started actions. Force stop cancels immediately; actions already started may have had effects.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private fun String.label() = lowercase().replace('_', ' ')

private fun time(millis: Long) = DateFormat.getTimeInstance().format(Date(millis))
