package com.colonelpanic.eva.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.conversation.ProviderStatus
import com.colonelpanic.eva.conversation.Thread
import com.colonelpanic.eva.providers.openai.OpenAiModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Destination(
    val label: String,
) {
    CONVERSATION("Conversation"),
    TOOLS("Tools"),
    SETTINGS("Settings"),
}

@Composable
internal fun DesktopApp(
    host: DesktopHost,
    onQuit: () -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val state by host.controller.state.collectAsState()
    val configuration by host.configuration.state.collectAsState()
    var destination by remember { mutableStateOf(Destination.CONVERSATION) }
    var threads by remember { mutableStateOf(emptyList<Thread>()) }
    var problem by remember { mutableStateOf<String?>(null) }
    val idle = !state.isLoading && !state.isSubmitting && !state.working && state.providerStatus != ProviderStatus.CONNECTING
    LaunchedEffect(host) {
        threads = withContext(Dispatchers.IO) { host.store.threads() }
        host.store.changes.collect { threads = withContext(Dispatchers.IO) { host.store.threads() } }
    }
    LaunchedEffect(host) {
        try {
            host.controller.state.first { !it.isLoading }
            if (host.controller.state.value.threadId == null) startThread(host.controller, Dispatchers.Main)
            connect(host.controller, Dispatchers.Main)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            problem = failure.message ?: "Could not connect."
        }
    }

    fun navigate(to: Destination) {
        destination = to
        scope.launch { drawer.close() }
    }

    fun openMenu() {
        scope.launch { drawer.open() }
    }

    suspend fun reconnect(): String? {
        host.controller.disconnect()
        return connect(host.controller, Dispatchers.Main)
    }
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(Modifier.width(320.dp)) {
                LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item { Text("EVA", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineMedium) }
                    items(Destination.entries) { page ->
                        NavigationDrawerItem(label = { Text(page.label) }, selected = destination == page, onClick = { navigate(page) })
                    }
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        Text("Conversations", Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall)
                        TextButton(enabled = idle, onClick = {
                            scope.launch {
                                try {
                                    reconnectToNewThread(host.controller, Dispatchers.Main)
                                    navigate(Destination.CONVERSATION)
                                } catch (
                                    cancelled: CancellationException,
                                ) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    problem = failure.message
                                }
                            }
                        }) { Text("New conversation") }
                    }
                    items(threads, key = { it.id }) { thread ->
                        NavigationDrawerItem(
                            selected = destination == Destination.CONVERSATION && state.threadId == thread.id,
                            label = { Text(thread.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            onClick = {
                                if (idle) {
                                    host.controller.showThread(thread.id)
                                    navigate(Destination.CONVERSATION)
                                }
                            },
                        )
                    }
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        TextButton(onClick = onQuit) { Text("Quit EVA") }
                    }
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            (problem ?: configuration.error)?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
            when (destination) {
                Destination.CONVERSATION -> Conversation(host.controller, ::openMenu)
                Destination.SETTINGS -> DesktopSettings(host, idle, ::openMenu) { reconnect() }
                Destination.TOOLS -> DesktopTools(host, idle, ::openMenu) { reconnect() }
            }
        }
    }
}

@Composable
private fun PageHeader(
    title: String,
    onMenu: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onMenu,
            Modifier.semantics {
                contentDescription = "Open navigation menu"
            },
        ) { Text("☰", style = MaterialTheme.typography.headlineSmall) }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun DesktopSettings(
    host: DesktopHost,
    idle: Boolean,
    onMenu: () -> Unit,
    reconnect: suspend () -> String?,
) {
    val saved by host.configuration.state.collectAsState()
    val scope = rememberCoroutineScope()
    var model by remember(saved) { mutableStateOf(saved.configuration.models.text) }
    var effort by remember(saved) { mutableStateOf(saved.configuration.models.reasoningEffort) }
    var choosing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var yaml by remember(saved) { mutableStateOf(saved.text) }
    var notice by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun save(block: () -> Unit) {
        scope.launch {
            saving = true
            notice = null
            try {
                withContext(Dispatchers.IO) { block() }
                val connected = reconnect()
                notice =
                    if (connected !=
                        null
                    ) {
                        "Saved to eva.yaml."
                    } else {
                        "Saved to eva.yaml, but ${connectionProblem(host.controller.state.value)}"
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                notice = failure.message ?: "The settings could not be saved."
            } finally {
                saving = false
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader("Settings", onMenu)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Text model", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    model,
                    { model = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Model") },
                    singleLine = true,
                    enabled = !saving,
                )
            }
            item {
                Text("Reasoning effort", style = MaterialTheme.typography.titleMedium)
                Column {
                    TextButton(onClick = { choosing = true }, enabled = !saving) { Text(effort) }
                    DropdownMenu(choosing, { choosing = false }) {
                        OpenAiModels.TEXT_REASONING_EFFORTS.forEach { value ->
                            DropdownMenuItem(text = { Text(value) }, onClick = {
                                effort =
                                    value
                                ; choosing = false
                            })
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = { save { host.configuration.saveModels(model, effort) } },
                    enabled =
                        idle && !saving && model.isNotBlank() && saved.error == null,
                ) { Text("Save settings") }
                if (!idle) Text("Settings can be saved when the current request finishes.", style = MaterialTheme.typography.bodySmall)
                notice?.let { Text(it) }
            }
            item {
                HorizontalDivider()
                Text("Configuration", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
                Text(host.configuration.file.absolutePath, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Model and prompt settings use the same eva.yaml format as the phone. Link this file to your configuration repository to restore them. Includes are preserved; phone-only features are not run on the desktop.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row {
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { host.configuration.reload() }
                            if (host.configuration.state.value.error == null) reconnect()
                            notice =
                                "Reloaded configuration."
                        }
                    }, enabled = idle && !saving) { Text("Reload") }
                    TextButton(onClick = { editing = !editing }) { Text(if (editing) "Close editor" else "Edit eva.yaml") }
                }
            }
            if (editing) {
                item {
                    OutlinedTextField(yaml, {
                        yaml = it
                    }, Modifier.fillMaxWidth(), label = { Text("eva.yaml") }, minLines = 12, maxLines = 24, enabled = !saving)
                    Button(
                        onClick = { save { host.configuration.saveText(yaml, saved.text) } },
                        enabled = idle && !saving,
                    ) { Text("Validate and save") }
                }
            }
            item {
                HorizontalDivider()
                Text("Skills", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
                host.configuration.enabledSkills().forEach { skill ->
                    Text(skill.displayName ?: skill.name, style = MaterialTheme.typography.titleSmall)
                    Text(skill.description, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Skills load from skills/ or .agents/skills/ beside eva.yaml, or skills.installed in the configuration. Disable one with skills.disabled.",
                    style = MaterialTheme.typography.bodySmall,
                )
                saved.resolved.notices.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            item {
                HorizontalDivider()
                Text("ChatGPT account", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
                Text(if (host.tokens.signedIn) "Signed in" else "Sign in with eva-desktop login")
                Text("Credentials stay on this computer and are not written to eva.yaml.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DesktopTools(
    host: DesktopHost,
    idle: Boolean,
    onMenu: () -> Unit,
    reconnect: suspend () -> String?,
) {
    val settings by host.extensions.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PageHeader("Tools", onMenu)
        Text("Local MCP servers", style = MaterialTheme.typography.titleMedium)
        Text(host.paths.mcpServers.absolutePath, style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { host.extensions.refresh() }) { Text("Refresh tools") }
            TextButton(onClick = { scope.launch { reconnect() } }, enabled = idle) { Text("Apply to conversation") }
        }
        Text("Newly enabled tools are available after applying or starting a new conversation.", style = MaterialTheme.typography.bodySmall)
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        settings.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (settings.entries.isEmpty()) {
                item {
                    Text(
                        "No MCP servers are configured. Add a server to mcp-servers.json, then restart EVA.",
                    )
                }
            }
            items(settings.entries, key = { it.installed.packageName }) { entry ->
                val installed = entry.installed
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(installed.descriptor?.title ?: installed.packageName, style = MaterialTheme.typography.titleMedium)
                    installed.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    installed.descriptor?.let { descriptor ->
                        Row {
                            TextButton(onClick = { host.extensions.enableAll(entry.key) }) { Text("Enable all") }
                            TextButton(
                                onClick = { host.extensions.enable(entry.key, false) },
                                enabled = entry.enabled,
                            ) { Text("Disable server") }
                        }
                        descriptor.capabilities.forEach { tool ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = entry.enabled && tool.name in entry.mutations, onCheckedChange = { allowed ->
                                    scope.launch {
                                        try {
                                            if (allowed && !entry.enabled) {
                                                host.extensions.enable(entry.key, true)
                                                kotlinx.coroutines.withTimeout(20_000) {
                                                    host.extensions.settings.first {
                                                        it.error != null ||
                                                            it.entries.any { current -> current.key == entry.key && current.enabled }
                                                    }
                                                }
                                                check(
                                                    host.extensions.settings.value.error == null,
                                                ) {
                                                    host.extensions.settings.value.error
                                                        .orEmpty()
                                                }
                                            }
                                            host.extensions.mutation(entry.key, tool.name, allowed)
                                        } catch (
                                            cancelled: CancellationException,
                                        ) {
                                            throw cancelled
                                        } catch (
                                            failure: Exception,
                                        ) {
                                            problem = failure.message ?: "The tool could not be enabled."
                                        }
                                    }
                                })
                                Column(Modifier.weight(1f)) {
                                    Text(tool.title)
                                    Text(tool.description, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    host.mcp.notes[installed.packageName]?.unsupported?.forEach { (tool, reason) ->
                        Text("$tool: $reason", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
