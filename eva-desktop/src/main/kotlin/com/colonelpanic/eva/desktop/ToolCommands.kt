package com.colonelpanic.eva.desktop

import com.colonelpanic.eva.capability.extensions.ExtensionSettingsEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** `eva-desktop tools`: each MCP server, its tools, which the user allowed, and what EVA cannot offer. */
internal suspend fun listTools(host: DesktopHost): Int {
    if (!awaitExtensions(host)) return 1
    val entries = host.extensions.settings.value.entries
    if (entries.isEmpty()) {
        println("No MCP servers are configured. Add them to ${'$'}XDG_CONFIG_HOME/eva/mcp-servers.json.")
        return 0
    }
    for (entry in entries) {
        val name = entry.installed.packageName
        println(entry.installed.problem?.let { "$name: $it" } ?: name)
        entry.installed.descriptor?.capabilities?.forEach { tool ->
            val allowed = entry.enabled && tool.name in entry.mutations
            println("  [${if (allowed) "x" else " "}] ${tool.name}")
        }
        val notes = host.mcp.notes[name]
        notes?.unsupported?.forEach { (tool, why) -> println("  [-] $tool: unavailable, $why") }
        notes?.hidden?.forEach { (tool, parameters) -> println("      $tool: ${parameters.joinToString()} not offered") }
    }
    return 0
}

/** `eva-desktop allow` or `deny` with a server and optionally tools; with no tools, the whole server. */
internal suspend fun setTools(
    host: DesktopHost,
    allow: Boolean,
    arguments: List<String>,
): Int {
    val server = arguments.firstOrNull() ?: return System.err.println("Name an MCP server.").let { 2 }
    if (!awaitExtensions(host)) return 1
    val entry = find(host, server) ?: return System.err.println("No MCP server named $server offers tools.").let { 1 }
    val offered =
        entry.installed.descriptor
            ?.capabilities
            ?.map { it.name }
            .orEmpty()
    val tools = arguments.drop(1).ifEmpty { offered }
    tools.filter { it !in offered }.takeIf { it.isNotEmpty() }?.let {
        System.err.println("$server does not offer ${it.joinToString()}.")
        return 1
    }
    when {
        allow && arguments.size == 1 -> {
            host.extensions.enableAll(entry.key)
        }

        allow -> {
            // A tool grant needs its server enabled first; both are applied in turn, not raced.
            if (!entry.enabled) {
                host.extensions.enable(entry.key, true)
                withTimeoutOrNull(SETTLE_MILLIS) {
                    host.extensions.settings.first { it.entries.any { e -> e.key == entry.key && e.enabled } }
                }
                    ?: return System.err.println(settledError(host)).let { 1 }
            }
            tools.forEach { host.extensions.mutation(entry.key, it, true) }
        }

        arguments.size == 1 -> {
            host.extensions.enable(entry.key, false)
        }

        else -> {
            tools.forEach { host.extensions.mutation(entry.key, it, false) }
        }
    }
    val settled =
        withTimeoutOrNull(SETTLE_MILLIS) {
            host.extensions.settings.first { settings ->
                val now = settings.entries.find { it.installed.packageName == server } ?: return@first false
                if (allow) {
                    now.enabled && now.mutations.containsAll(tools)
                } else {
                    (arguments.size == 1 && !now.enabled) ||
                        now.mutations.none { it in tools }
                }
            }
        }
    if (settled == null) return System.err.println(settledError(host)).let { 1 }
    return listTools(host)
}

private fun find(
    host: DesktopHost,
    server: String,
): ExtensionSettingsEntry? =
    host.extensions.settings.value.entries.find {
        it.installed.packageName == server &&
            it.installed.descriptor != null
    }

private suspend fun awaitExtensions(host: DesktopHost): Boolean {
    if (withTimeoutOrNull(SETTLE_MILLIS) { host.extensions.awaitReady() } != null) return true
    System.err.println("The MCP servers did not report their tools in time.")
    return false
}

private fun settledError(host: DesktopHost) = host.extensions.settings.value.error ?: "The change was not saved."

private const val SETTLE_MILLIS = 20_000L
