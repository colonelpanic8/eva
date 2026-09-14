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
import java.util.UUID

class ExtensionBackend(
    private val identity: ExtensionIdentity,
    private val descriptor: Descriptor,
    private val capability: Capability,
    private val connections: ExtensionConnectionManager,
    private val authorization: () -> String?,
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

    override suspend fun unavailableReason(): String? = authorization()

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome =
        ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "Extension execution requires a journaled invocation.")

    override suspend fun execute(proposal: ToolProposal): ExecutionOutcome =
        withContext(worker) {
            authorization()?.let { return@withContext ExecutionOutcome(InvocationStatus.NOT_EXECUTED, it) }
            val arguments =
                try {
                    ExtensionProtocol.encodeArguments(capability.inputSchema, proposal.arguments)
                } catch (_: IllegalArgumentException) {
                    return@withContext ExecutionOutcome(
                        InvocationStatus.NOT_EXECUTED,
                        "Extension arguments were rejected. Nothing was submitted.",
                    )
                }
            val id =
                proposal.callId.takeIf { it.length in 1..256 && it.all { char -> char.code in 1..127 } }
                    ?: UUID.nameUUIDFromBytes(proposal.callId.toByteArray(Charsets.UTF_8)).toString()
            when (val exchange = connections.execute(identity, id, descriptor.revision, capability, arguments)) {
                is ExtensionExchange.Unavailable -> {
                    ExecutionOutcome(InvocationStatus.NOT_EXECUTED, exchange.message)
                }

                ExtensionExchange.Uncertain -> {
                    unknown()
                }

                is ExtensionExchange.Reply -> {
                    val result =
                        runCatching { ExtensionProtocol.executeResult(exchange.json, capability.maxResultBytes) }.getOrNull()
                            ?: return@withContext unknown()
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

    private fun unknown() = ExecutionOutcome(InvocationStatus.UNKNOWN, CapabilityDispatcher.UNKNOWN_MESSAGE)
}
