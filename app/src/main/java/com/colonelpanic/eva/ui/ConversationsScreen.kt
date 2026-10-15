package com.colonelpanic.eva.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.colonelpanic.eva.conversation.ThreadSummary
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationsScreen(
    threads: List<ThreadSummary>,
    onOpenDrawer: () -> Unit,
    onNewThread: () -> Unit,
    onShowThread: (String) -> Unit,
) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Conversations") },
            actions = { TextButton(onClick = onNewThread) { Text("New") } },
            navigationIcon = { MenuButton(onOpenDrawer) },
            colors = evaTopAppBarColors(),
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (threads.isEmpty()) item { Text("No conversations yet.") }
            items(threads.sortedByDescending { it.working }, key = { it.id }) { thread ->
                Card(onClick = { onShowThread(thread.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(thread.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(thread.updatedAtMillis)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (thread.working) Text("Working…", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
