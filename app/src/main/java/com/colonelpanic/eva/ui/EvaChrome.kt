package com.colonelpanic.eva.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.R
import com.colonelpanic.eva.conversation.ThreadSummary

/**
 * The app bar wears [androidx.compose.material3.ColorScheme.primaryContainer] rather than
 * `primary`: it reads as EVA's blue in both themes, where a full-strength primary bar would
 * be a glare in the dark one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun evaTopAppBarColors(): TopAppBarColors =
    TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )

@Composable
internal fun MenuButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Filled.Menu, contentDescription = "Open navigation menu")
    }
}

/** Top-level destinations, plus the connection status that belongs to neither screen. */
@Composable
internal fun EvaDrawerSheet(
    providerLabel: String,
    current: EvaDestination,
    onSelect: (EvaDestination) -> Unit,
    threads: List<ThreadSummary> = emptyList(),
    shownThreadId: String? = null,
    onNewThread: () -> Unit = {},
    onShowThread: (String) -> Unit = {},
) {
    // The sheet's own insets are dropped so the header's blue runs behind the status bar
    // instead of leaving a bare surface strip above it.
    ModalDrawerSheet(windowInsets = WindowInsets(0)) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = providerLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(12.dp))
        DrawerDestination("Conversation", Icons.Filled.Home, EvaDestination.CONVERSATION, current, onSelect)
        DrawerDestination("Extensions", Icons.Filled.List, EvaDestination.EXTENSIONS, current, onSelect)
        DrawerDestination("Prompt", Icons.Filled.Edit, EvaDestination.PROMPT, current, onSelect)
        DrawerDestination("Settings", Icons.Filled.Settings, EvaDestination.SETTINGS, current, onSelect)
        DrawerDestination("About", Icons.Filled.Info, EvaDestination.ABOUT, current, onSelect)
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        NavigationDrawerItem(
            label = { Text("New conversation") },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            selected = false,
            onClick = onNewThread,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
        LazyColumn {
            items(threads, key = { it.id }) { thread ->
                NavigationDrawerItem(
                    label = { Text(thread.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    badge = { if (thread.working) Text("Working…", style = MaterialTheme.typography.labelSmall) },
                    selected = thread.id == shownThreadId && current == EvaDestination.CONVERSATION,
                    onClick = { onShowThread(thread.id) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        }
    }
}

@Composable
private fun DrawerDestination(
    label: String,
    icon: ImageVector,
    destination: EvaDestination,
    current: EvaDestination,
    onSelect: (EvaDestination) -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(label) },
        icon = { Icon(icon, contentDescription = null) },
        selected = destination == current,
        onClick = { onSelect(destination) },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}
