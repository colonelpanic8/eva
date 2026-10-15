package com.colonelpanic.eva.capability.extensions

import com.colonelpanic.eva.capability.CapabilityDefinition
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilitySource
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.SecretVault
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.conversation.prompt.Wording
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

class ExtensionBackend(
    private val identity: ExtensionIdentity,
    private val descriptor: Descriptor,
    private val capability: Capability,
    private val connections: ExtensionConnectionManager,
    private val worker: CoroutineDispatcher = Dispatchers.IO,
    private val secrets: SecretVault? = null,
    private val wording: () -> Wording = { Wording.bundled },
) : ExecutionBackend {
    val definition =
        CapabilityDefinition(
            id = "extension.${identity.packageName}.${capability.name}",
            title = capability.title,
            description = capability.description,
            inputSchema = capability.inputSchema,
            readOnly = capability.effect == Effect.READ,
            source = CapabilitySource(identity.component, descriptor.title),
            endsVoiceCall = capability.endsVoiceCall,
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

                is ExtensionExchange.Oversized -> {
                    oversized(invocation, exchange.limit)
                }

                is ExtensionExchange.Reply -> {
                    val result =
                        runCatching {
                            ExtensionProtocol.executeResult(exchange.json, capability.maxResultBytes, capability.outputSchema)
                        }.getOrNull()
                            ?: return@withContext unknown(invocation)
                    withheld(
                        proposal,
                        result.outcome.copy(
                            message =
                                buildString {
                                    result.reasonCode?.let { append("Reason: ").append(it).append(". ") }
                                    if (result.truncated) append("Provider reports incomplete results. ")
                                    append(result.outcome.message)
                                },
                        ),
                        result.secrets,
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

    /**
     * Moves a reply's secrets into the vault before the outcome reaches the journal or a model, which
     * see only handles and labels. Text echoing a value is masked as well.
     */
    private fun withheld(
        proposal: ToolProposal,
        outcome: ExecutionOutcome,
        returned: Map<String, ExtensionSecret>,
    ): ExecutionOutcome {
        if (returned.isEmpty()) return outcome
        val vault = secrets ?: return outcome.copy(message = outcome.message + "\n" + wording().message(Wording.SECRETS_UNSUPPORTED))
        val handles = returned.mapValues { (_, secret) -> vault.store(proposal.threadId, secret.value, secret.label) to secret.label }
        val listing =
            handles.entries.joinToString("\n") { (name, entry) -> "- $name: ${entry.first} (${JsonPrimitive(entry.second)})" }
        val data =
            JsonObject(
                (outcome.data?.let { vault.mask(it) as JsonObject } ?: JsonObject(emptyMap())) +
                    (
                        "secrets" to
                            JsonObject(
                                handles.mapValues { (_, entry) ->
                                    buildJsonObject {
                                        put("handle", entry.first)
                                        put("label", entry.second)
                                    }
                                },
                            )
                    ),
            )
        return outcome.copy(
            message = vault.mask(outcome.message) + "\n" + wording().message(Wording.SECRETS_WITHHELD).replace("{secrets}", listing),
            data = data,
        )
    }

    /** A read-only capability changed nothing, so its discarded reply is a plain failure; anything else may have run. */
    private fun oversized(
        invocation: String,
        limit: Int,
    ): ExecutionOutcome {
        val discarded = "The extension's reply exceeded EVA's $limit-byte result limit and was discarded."
        return if (definition.readOnly) {
            ExecutionOutcome(InvocationStatus.FAILED, "$discarded Ask it for less, such as a narrower query or a smaller limit.")
        } else {
            unknown(invocation, "$discarded The action may have run.")
        }
    }

    /** The ID lets a provider's own status read reconcile a reply EVA never received. */
    private fun unknown(
        invocation: String,
        reason: String? = null,
    ) = ExecutionOutcome(
        InvocationStatus.UNKNOWN,
        listOfNotNull(reason, CapabilityDispatcher.UNKNOWN_MESSAGE, "Invocation ID: $invocation.").joinToString(" "),
        buildJsonObject { put("invocationId", invocation) },
    )
}
