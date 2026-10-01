package com.colonelpanic.eva.desktop.mcp

import com.colonelpanic.eva.capability.BoundedJson
import com.colonelpanic.eva.capability.CapabilityDefinition
import com.colonelpanic.eva.capability.CapabilitySource
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.capability.extensions.AdapterIdentity
import com.colonelpanic.eva.capability.extensions.Capability
import com.colonelpanic.eva.capability.extensions.CapabilityAdapter
import com.colonelpanic.eva.capability.extensions.CapabilityBinding
import com.colonelpanic.eva.capability.extensions.Descriptor
import com.colonelpanic.eva.capability.extensions.ExtensionProtocol
import com.colonelpanic.eva.capability.extensions.InstalledExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** A local MCP server EVA starts over stdio, as `mcpServers` entries name them in other MCP clients. */
data class McpServerConfig(
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
) {
    init {
        require(NAME.matches(name)) { "MCP server names are lowercase letters, digits, and dashes: $name" }
        require(command.isNotBlank()) { "MCP server $name has no command" }
    }

    companion object {
        private val NAME = Regex("[a-z0-9][a-z0-9-]{0,31}")

        /** Reads `{"mcpServers": {"name": {"command": ..., "args": [...], "env": {...}}}}`; no file means no servers. */
        fun load(file: File): List<McpServerConfig> {
            if (!file.isFile) return emptyList()
            val servers = Json.parseToJsonElement(file.readText()).jsonObject["mcpServers"]?.jsonObject ?: return emptyList()
            return servers.map { (name, entry) ->
                val server = entry.jsonObject
                McpServerConfig(
                    name,
                    requireNotNull(server["command"]?.jsonPrimitive?.content) { "MCP server $name has no command" },
                    server["args"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                    server["env"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
                )
            }
        }
    }
}

/** A configured server; changing its command or arguments makes it a different identity with no grants. */
data class McpIdentity(
    val config: McpServerConfig,
) : AdapterIdentity {
    override val instanceId: String get() = "mcp:${config.name}"
    override val key: String get() =
        BoundedJson.digest(JsonArray((listOf(instanceId, config.command) + config.args).map(::JsonPrimitive)))
}

/** The calls the adapter needs from a running server; [McpConnection] speaks them over stdio. */
interface McpSession : AutoCloseable {
    val serverName: String
    val serverVersion: String

    fun alive(): Boolean

    suspend fun tools(): List<McpToolListing>

    /** Throws [McpNotSent] when the request never left EVA; any other failure leaves the outcome unknown. */
    suspend fun call(
        tool: String,
        arguments: JsonObject,
        timeoutMillis: Long,
    ): McpCallReply
}

class McpNotSent(
    message: String,
) : Exception(message)

/**
 * Local MCP servers as EVA extensions. Each server's tools pass EVA's extension rules and grants,
 * and every call goes through the dispatcher and its journal like any other action.
 */
class McpAdapter(
    private val servers: List<McpServerConfig>,
    private val scope: CoroutineScope,
    private val start: suspend (McpServerConfig) -> McpSession = McpConnection::start,
) : CapabilityAdapter,
    AutoCloseable {
    data class Notes(
        val unsupported: Map<String, String> = emptyMap(),
        val hidden: Map<String, List<String>> = emptyMap(),
    )

    private val mutableInstalled = MutableStateFlow<List<InstalledExtension>>(emptyList())
    private val mutableReady = MutableStateFlow(false)
    override val installed: StateFlow<List<InstalledExtension>> = mutableInstalled.asStateFlow()
    override val ready: StateFlow<Boolean> = mutableReady.asStateFlow()

    private val scans = Mutex()
    private val sessions = mutableMapOf<String, McpSession>()

    @Volatile var notes: Map<String, Notes> = emptyMap()
        private set

    override fun refresh() {
        scope.launch { scan() }
    }

    /** Starts any server that is not running, lists its tools, and publishes what EVA can offer. */
    suspend fun scan() =
        scans.withLock {
            val found = mutableListOf<InstalledExtension>()
            val collected = mutableMapOf<String, Notes>()
            for (config in servers) {
                val identity = McpIdentity(config)
                val entry =
                    try {
                        val session =
                            sessions[config.name]?.takeIf { it.alive() } ?: start(config).also { started ->
                                sessions.remove(config.name)?.close()
                                sessions[config.name] = started
                            }
                        val translation = McpTools.translate(config.name, session.serverVersion, session.tools())
                        collected[config.name] = Notes(translation.unsupported, translation.hidden)
                        InstalledExtension(
                            config.name,
                            identity,
                            translation.descriptor,
                            problem = if (translation.descriptor == null) "It offers no tools EVA can use." else null,
                            capabilityPrefix = "mcp.${config.name}",
                            androidPackages = emptyList(),
                        )
                    } catch (failure: Exception) {
                        sessions.remove(config.name)?.close()
                        InstalledExtension(
                            config.name,
                            identity,
                            null,
                            problem = "It could not be started: ${failure.message ?: failure.javaClass.simpleName}",
                            capabilityPrefix = "mcp.${config.name}",
                            androidPackages = emptyList(),
                        )
                    }
                found += entry
            }
            notes = collected
            mutableInstalled.value = found
            mutableReady.value = true
        }

    override fun invalidate(
        packageName: String,
        removed: Boolean,
    ) = refresh()

    override fun available(
        identity: AdapterIdentity,
        digest: String,
    ): Boolean {
        val config = (identity as? McpIdentity)?.config ?: return false
        val live =
            installed.value
                .find { it.identity == identity }
                ?.descriptor
                ?.digest == digest
        return live && sessions[config.name]?.alive() == true
    }

    override fun bindings(extension: InstalledExtension): List<CapabilityBinding> {
        val identity = extension.identity as? McpIdentity ?: return emptyList()
        val descriptor = extension.descriptor ?: return emptyList()
        return descriptor.capabilities.map { capability ->
            val backend = McpBackend(identity, descriptor, capability) { sessions[identity.config.name] }
            CapabilityBinding(capability, backend.definition, backend, "${identity.key}:${descriptor.digest}")
        }
    }

    override fun close() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
    }
}

/** One MCP tool as an EVA capability. Its effect is unknown, so it runs only with its own grant. */
class McpBackend(
    private val identity: McpIdentity,
    descriptor: Descriptor,
    private val capability: Capability,
    private val session: () -> McpSession?,
) : ExecutionBackend {
    val definition =
        CapabilityDefinition(
            id = "mcp.${identity.config.name}.${capability.name}",
            title = capability.title,
            description = capability.description,
            inputSchema = capability.inputSchema,
            readOnly = false,
            source = CapabilitySource(identity.instanceId, descriptor.title),
        )

    override suspend fun unavailableReason(): String? =
        if (session()?.alive() == true) null else "The ${identity.config.name} MCP server is not running."

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome =
        ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "MCP tools run only as journaled invocations.")

    override suspend fun execute(proposal: ToolProposal): ExecutionOutcome {
        val arguments =
            try {
                Json.parseToJsonElement(ExtensionProtocol.encodeArguments(capability.inputSchema, proposal.arguments)).jsonObject
            } catch (_: IllegalArgumentException) {
                return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "The tool's arguments were rejected. Nothing was sent.")
            }
        val running =
            session()?.takeIf { it.alive() }
                ?: return ExecutionOutcome(
                    InvocationStatus.NOT_EXECUTED,
                    "The ${identity.config.name} MCP server is not running. Nothing was sent.",
                )
        return try {
            McpTools.outcome(running.call(capability.name, arguments, capability.maxWaitMillis))
        } catch (failure: McpNotSent) {
            ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "Nothing was sent: ${failure.message}")
        } catch (failure: Exception) {
            ExecutionOutcome(
                InvocationStatus.UNKNOWN,
                "The ${identity.config.name} MCP server did not answer, so EVA cannot tell whether the tool ran. " +
                    "Check before trying again.",
            )
        }
    }
}
