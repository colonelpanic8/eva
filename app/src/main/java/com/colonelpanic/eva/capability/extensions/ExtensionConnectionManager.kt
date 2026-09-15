package com.colonelpanic.eva.capability.extensions

import com.colonelpanic.eva.capability.BoundedJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ExtensionIdentity(
    val user: Int,
    val packageName: String,
    val component: String,
    val signer: String,
    val uid: Int,
    val installedAt: Long,
) : AdapterIdentity {
    override val instanceId: String get() = "installed:$user:$packageName"
    override val key: String get() =
        BoundedJson.digest(
            JsonArray(listOf(user.toString(), packageName, component, signer, installedAt.toString()).map(::JsonPrimitive)),
        )
}

data class ExtensionCandidate(
    val identity: ExtensionIdentity,
    val exported: Boolean,
    val enabled: Boolean,
    val version: Int,
)

data class ExtensionListing(
    val packageName: String,
    val identity: ExtensionIdentity?,
    val problem: String?,
)

fun selectExtensions(candidates: List<ExtensionCandidate>): List<ExtensionListing> =
    candidates.groupBy { it.identity.packageName }.toSortedMap().map { (name, services) ->
        val service = services.singleOrNull()
        when {
            service == null -> {
                ExtensionListing(name, null, "Multiple extension services advertised; package rejected.")
            }

            !service.exported || !service.enabled || service.version != 1 -> {
                ExtensionListing(name, null, "Extension service is disabled, private, or has an unsupported protocol version.")
            }

            else -> {
                ExtensionListing(name, service.identity, null)
            }
        }
    }

interface ExtensionConnection : AutoCloseable {
    fun describe(
        id: String,
        request: String,
        deadline: Long,
        callback: (Int, String, String) -> Unit,
    )

    fun execute(
        id: String,
        revision: String,
        capability: String,
        arguments: String,
        deadline: Long,
        callback: (Int, String, String) -> Unit,
    )
}

fun interface ExtensionConnector {
    suspend fun connect(
        identity: ExtensionIdentity,
        died: () -> Unit,
    ): ExtensionConnection
}

sealed interface ExtensionExchange {
    data class Reply(
        val json: String,
    ) : ExtensionExchange

    data class Unavailable(
        val message: String,
    ) : ExtensionExchange

    data object Uncertain : ExtensionExchange
}

/** One in-flight transaction per package; no queue and no automatic retry. */
class ExtensionConnectionManager(
    private val connector: ExtensionConnector,
    private val elapsedMillis: () -> Long,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun describe(identity: ExtensionIdentity): ExtensionExchange =
        exchange(identity, UUID.randomUUID().toString(), 5_000, ExtensionProtocol.CATALOG_BYTES) { connection, id, deadline, callback ->
            connection.describe(id, ExtensionProtocol.describeRequest(), deadline, callback)
        }

    suspend fun execute(
        identity: ExtensionIdentity,
        invocationId: String,
        revision: String,
        capability: Capability,
        arguments: String,
    ): ExtensionExchange {
        ExtensionProtocol.arguments(capability.inputSchema, arguments)
        require(invocationId.length in 1..256 && invocationId.all { it.code in 1..127 })
        return exchange(
            identity,
            invocationId,
            capability.maxWaitMillis,
            capability.maxResultBytes,
        ) { connection, id, deadline, callback ->
            connection.execute(id, revision, capability.name, arguments, deadline, callback)
        }
    }

    private suspend fun exchange(
        identity: ExtensionIdentity,
        id: String,
        duration: Long,
        maxBytes: Int,
        submit: (ExtensionConnection, String, Long, (Int, String, String) -> Unit) -> Unit,
    ): ExtensionExchange {
        val lock = locks.getOrPut("${identity.user}:${identity.packageName}") { Mutex() }
        if (!lock.tryLock()) return ExtensionExchange.Unavailable("Extension is busy. Nothing was submitted.")
        val reply = CompletableDeferred<ExtensionExchange>()
        var connection: ExtensionConnection? = null
        var submitted = false
        val deadline = elapsedMillis() + duration
        try {
            return withTimeoutOrNull(duration) {
                connection = connector.connect(identity) { reply.complete(ExtensionExchange.Uncertain) }
                if (reply.isCompleted || elapsedMillis() >= deadline) {
                    return@withTimeoutOrNull ExtensionExchange.Unavailable("Extension disconnected or deadline expired before submission.")
                }
                submitted = true
                submit(checkNotNull(connection), id, deadline) { uid, requestId, json ->
                    if (uid == identity.uid && requestId == id && elapsedMillis() < deadline && !reply.isCompleted) {
                        reply.complete(
                            if (json.length > maxBytes || json.toByteArray(Charsets.UTF_8).size > maxBytes) {
                                ExtensionExchange.Uncertain
                            } else {
                                ExtensionExchange.Reply(json)
                            },
                        )
                    }
                }
                reply.await()
            } ?: failure(submitted)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return failure(submitted)
        } finally {
            reply.cancel()
            runCatching { connection?.close() }
            lock.unlock()
        }
    }

    private fun failure(submitted: Boolean): ExtensionExchange =
        if (submitted) {
            ExtensionExchange.Uncertain
        } else {
            ExtensionExchange.Unavailable(
                "Extension could not be reached. Nothing was submitted.",
            )
        }
}
