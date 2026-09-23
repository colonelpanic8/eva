package com.colonelpanic.eva.capability.extensions

import com.colonelpanic.eva.capability.CapabilityDefinition
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilitySource
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

class ExtensionBackend(
    private val identity: ExtensionIdentity,
    private val descriptor: Descriptor,
    private val capability: Capability,
    private val connections: ExtensionConnectionManager,
    private val worker: CoroutineDispatcher = Dispatchers.IO,
) : ExecutionBackend {
    val definition =
        CapabilityDefinition(
            id = "extension.${identity.packageName}.${capability.name}",
            title = capability.title,
            description = capability.description,
            inputSchema = capability.inputSchema,
            readOnly = capability.effect == Effect.READ,
            source = CapabilitySource(identity.component, descriptor.title),
        )

    override suspend fun unavailableReason(): String? = null

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome =
        ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "Extension execution requires a journaled invocation.")

    override suspend fun execute(proposal: ToolProposal): ExecutionOutcome =
        withContext(worker) {
            val arguments =
                try {
                    ExtensionProtocol.encodeArguments(capability.inputSchema, proposal.arguments)
                } catch (_: IllegalArgumentException) {
                    return@withContext ExecutionOutcome(
                        InvocationStatus.NOT_EXECUTED,
                        "Extension arguments were rejected. Nothing was submitted.",
                    )
                }
            val invocation = invocationId(proposal.callId)
            when (val exchange = connections.execute(identity, invocation, descriptor.revision, capability, arguments)) {
                is ExtensionExchange.Unavailable -> {
                    ExecutionOutcome(InvocationStatus.NOT_EXECUTED, exchange.message)
                }

                ExtensionExchange.Uncertain -> {
                    unknown(invocation)
                }

                is ExtensionExchange.Reply -> {
                    val result =
                        runCatching {
                            ExtensionProtocol.executeResult(exchange.json, capability.maxResultBytes, capability.outputSchema)
                        }.getOrNull()
                            ?: return@withContext unknown(invocation)
                    result.outcome.copy(
                        message =
                            buildString {
                                result.reasonCode?.let { append("Reason: ").append(it).append(". ") }
                                if (result.truncated) append("Provider reports incomplete results. ")
                                append(result.outcome.message)
                            },
                    )
                }
            }
        }

    companion object {
        /**
         * The journal claims each call ID once, so this is stable for the invocation and never names
         * two. Providers key idempotent replay and status lookups on it.
         */
        fun invocationId(callId: String): String =
            "eva-" +
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(callId.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
    }

    /** The ID lets a provider's own status read reconcile a reply EVA never received. */
    private fun unknown(invocation: String) =
        ExecutionOutcome(
            InvocationStatus.UNKNOWN,
            "${CapabilityDispatcher.UNKNOWN_MESSAGE} Invocation ID: $invocation.",
            buildJsonObject { put("invocationId", invocation) },
        )
}
