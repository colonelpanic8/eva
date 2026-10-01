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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
 *
 * A server's session and the descriptor listed from it are published together, so a call only
 * ever reaches a session whose current tools match the contract the user granted. A server that
 * announces changed tools stops being available at once, until a fresh listing is published.
 */
class McpAdapter(
    private val servers: List<McpServerConfig>,
    private val scope: CoroutineScope,
    private val start: suspend (McpServerConfig, onToolsChanged: () -> Unit) -> McpSession = McpConnection::start,
) : CapabilityAdapter,
    AutoCloseable {
    data class Notes(
        val unsupported: Map<String, String> = emptyMap(),
        val hidden: Map<String, List<String>> = emptyMap(),
    )

    private class Published(
        val session: McpSession,
        val descriptor: Descriptor?,
    )

    private val mutableInstalled = MutableStateFlow<List<InstalledExtension>>(emptyList())
    private val mutableReady = MutableStateFlow(false)
    override val installed: StateFlow<List<InstalledExtension>> = mutableInstalled.asStateFlow()
    override val ready: StateFlow<Boolean> = mutableReady.asStateFlow()

    private val scans = Mutex()
    private val published = ConcurrentHashMap<String, Published>()

    /** Servers whose tools changed since their last listing, each with the notice that said so. */
    private val changed = ConcurrentHashMap<String, Long>()
    private val notices = AtomicLong()

    /** Sessions started but not yet published, closed if their listing never completes. */
    private val unpublished: MutableSet<McpSession> = ConcurrentHashMap.newKeySet()

    @Volatile var notes: Map<String, Notes> = emptyMap()
        private set

    override fun refresh() {
        scope.launch { scan() }
    }

    /** A listing, with the change notice it already reflects; a newer notice leaves the server withdrawn. */
    private class Listing(
        val entry: Published,
        val notes: Notes,
        val seenNotice: Long?,
    )

    /** Lists every server in parallel, starting any that is not running, and publishes what EVA can offer. */
    suspend fun scan() =
        scans.withLock {
            try {
                val results = coroutineScope { servers.map { config -> async { config to discover(config) } }.awaitAll() }
                val collected = mutableMapOf<String, Notes>()
                mutableInstalled.value =
                    results.map { (config, result) ->
                        val identity = McpIdentity(config)
                        result.fold(
                            onSuccess = { listing -> publish(config, identity, listing).also { collected[config.name] = listing.notes } },
                            onFailure = { failure ->
                                published.remove(config.name)?.session?.close()
                                installed(
                                    config,
                                    identity,
                                    null,
                                    "It could not be started: ${failure.message ?: failure.javaClass.simpleName}",
                                )
                            },
                        )
                    }
                notes = collected
                mutableReady.value = true
            } finally {
                // A scan cut short, as at shutdown, must not leave servers it started running unseen.
                unpublished.toList().forEach { session ->
                    unpublished -= session
                    session.close()
                }
            }
        }

    private fun publish(
        config: McpServerConfig,
        identity: McpIdentity,
        listing: Listing,
    ): InstalledExtension {
        val entry = listing.entry
        published
            .put(config.name, entry)
            ?.session
            ?.takeIf { it !== entry.session }
            ?.close()
        unpublished -= entry.session
        // Only the notice this listing already reflects is cleared; one that arrived meanwhile stands.
        listing.seenNotice?.let { changed.remove(config.name, it) }
        return installed(config, identity, entry.descriptor, if (entry.descriptor == null) "It offers no tools EVA can use." else null)
    }

    private suspend fun discover(config: McpServerConfig): Result<Listing> {
        val current = published[config.name]
        val seenNotice = changed[config.name]
        if (current != null && current.session.alive() && seenNotice == null) {
            return Result.success(Listing(current, notes[config.name] ?: Notes(), null))
        }
        val reused = current?.session?.takeIf { it.alive() }
        return runCatching {
            val session =
                reused ?: start(config) {
                    changed[config.name] = notices.incrementAndGet()
                    refresh()
                }.also { unpublished += it }
            try {
                val translation = McpTools.translate(config.name, session.serverVersion, session.tools())
                Listing(Published(session, translation.descriptor), Notes(translation.unsupported, translation.hidden), seenNotice)
            } catch (failure: Exception) {
                if (session !== reused) {
                    unpublished -= session
                    session.close()
                }
                throw failure
            }
        }
    }

    private fun installed(
        config: McpServerConfig,
        identity: McpIdentity,
        descriptor: Descriptor?,
        problem: String?,
    ) = InstalledExtension(
        config.name,
        identity,
        descriptor,
        problem = problem,
        capabilityPrefix = "mcp.${config.name}",
        androidPackages = emptyList(),
    )

    override fun invalidate(
        packageName: String,
        removed: Boolean,
    ) = refresh()

    override fun available(
        identity: AdapterIdentity,
        digest: String,
    ): Boolean = session(identity as? McpIdentity ?: return false, digest) != null

    /** The running session whose published tools are exactly [digest], or null. */
    internal fun session(
        identity: McpIdentity,
        digest: String,
    ): McpSession? {
        val name = identity.config.name
        val entry = published[name] ?: return null
        return entry.session.takeIf { name !in changed.keys && entry.descriptor?.digest == digest && it.alive() }
    }

    override fun bindings(extension: InstalledExtension): List<CapabilityBinding> {
        val identity = extension.identity as? McpIdentity ?: return emptyList()
        val descriptor = extension.descriptor ?: return emptyList()
        return descriptor.capabilities.map { capability ->
            val backend = McpBackend(identity, descriptor, capability) { session(identity, descriptor.digest) }
            CapabilityBinding(capability, backend.definition, backend, "${identity.key}:${descriptor.digest}")
        }
    }

    override fun close() {
        (published.values.map { it.session } + unpublished).forEach { runCatching { it.close() } }
        published.clear()
        unpublished.clear()
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
        if (session() != null) null else "The ${identity.config.name} MCP server is not running or changed its tools."

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
