package com.colonelpanic.eva.desktop.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolListChangedNotification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A server process EVA started, spoken to over its stdin and stdout. */
class McpConnection private constructor(
    private val process: Process,
    private val client: Client,
) : McpSession {
    override val serverName: String get() = client.serverVersion?.name.orEmpty()
    override val serverVersion: String get() = client.serverVersion?.version.orEmpty()

    override fun alive() = process.isAlive

    override suspend fun tools(): List<McpToolListing> {
        val tools = mutableListOf<McpToolListing>()
        var cursor: String? = null
        do {
            val page = withTimeout(LIST_TIMEOUT_MILLIS) { client.listTools(ListToolsRequest(cursor?.let { PaginatedRequestParams(it) })) }
            page.tools.forEach { tool ->
                tools +=
                    McpToolListing(
                        tool.name,
                        tool.title ?: tool.annotations?.title,
                        tool.description,
                        buildJsonObject {
                            put("type", tool.inputSchema.type)
                            tool.inputSchema.properties?.let { put("properties", it) }
                            tool.inputSchema.required?.let { put("required", JsonArray(it.map(::JsonPrimitive))) }
                        },
                    )
            }
            cursor = page.nextCursor
        } while (cursor != null && tools.size < MAX_LISTED_TOOLS)
        return tools
    }

    override suspend fun call(
        tool: String,
        arguments: JsonObject,
        timeoutMillis: Long,
    ): McpCallReply {
        if (!process.isAlive) throw McpNotSent("the MCP server had stopped")
        val result = withTimeout(timeoutMillis) { client.callTool(CallToolRequest(CallToolRequestParams(tool, arguments))) }
        val text = result.content.filterIsInstance<TextContent>().map { it.text }
        return McpCallReply(text, result.content.size - text.size, result.structuredContent, result.isError == true)
    }

    override fun close() {
        runCatching { runBlocking { client.close() } }
        process.destroy()
    }

    companion object {
        private const val LIST_TIMEOUT_MILLIS = 15_000L
        private const val MAX_LISTED_TOOLS = 256

        /** Starts the server and completes MCP initialization, or stops the process and throws. */
        suspend fun start(
            config: McpServerConfig,
            onToolsChanged: () -> Unit,
        ): McpSession {
            val process =
                ProcessBuilder(listOf(config.command) + config.args)
                    .apply { environment().putAll(config.env) }
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            val client = Client(Implementation("eva-desktop", VERSION))
            client.setNotificationHandler<ToolListChangedNotification>(Method.Defined.NotificationsToolsListChanged) {
                onToolsChanged()
                CompletableDeferred(Unit)
            }
            try {
                withTimeout(LIST_TIMEOUT_MILLIS) {
                    client.connect(
                        StdioClientTransport(process.inputStream.asSource().buffered(), process.outputStream.asSink().buffered()),
                    )
                }
            } catch (failure: Exception) {
                process.destroy()
                throw failure
            }
            return McpConnection(process, client)
        }

        private const val VERSION = "0.1.0"
    }
}
