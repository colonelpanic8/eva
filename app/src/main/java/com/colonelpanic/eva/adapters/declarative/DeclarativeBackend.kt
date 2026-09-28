package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.BoundedJson
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.capability.WaitBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

interface DeclarativeHost {
    suspend fun unavailableReason(binding: DeclarativeBinding): String?

    suspend fun launch(request: IntentRequest): ExecutionOutcome

    suspend fun query(
        request: ContentRequest,
        timeoutMillis: Long,
    ): ContentRows

    suspend fun request(
        request: HttpRequest,
        timeoutMillis: Long,
    ): HttpResponse
}

class DeclarativeBackend(
    private val capability: PackageCapability,
    private val host: DeclarativeHost,
    private val settings: () -> Map<String, JsonPrimitive> = { emptyMap() },
    private val pollMillis: Long = 1_000,
    private val budget: (ToolProposal) -> WaitBudget,
) : ExecutionBackend {
    override suspend fun unavailableReason(): String? = host.unavailableReason(capability.binding)

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome =
        ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "Extension execution requires a journaled invocation.")

    override suspend fun execute(proposal: ToolProposal): ExecutionOutcome {
        val wait = proposal.waitBudget ?: budget(proposal)
        val arguments: BindingArguments
        val binding: DeclarativeBinding
        val request: Any
        try {
            arguments = BindingArguments(capability, proposal.arguments, settings())
            binding = arguments.select(capability.binding)
            request =
                when (binding) {
                    is DeclarativeBinding.Select -> error("Nested selection is unsupported")
                    is DeclarativeBinding.Intent -> arguments.intent(binding)
                    is DeclarativeBinding.Content -> arguments.content(binding)
                    is DeclarativeBinding.Http -> arguments.http(binding)
                }
        } catch (_: Exception) {
            return ExecutionOutcome(
                InvocationStatus.NOT_EXECUTED,
                "Arguments could not fill the approved binding. Nothing was submitted. ${wait.receipt()}",
            )
        }
        val outcome =
            try {
                when (binding) {
                    is DeclarativeBinding.Select -> {
                        error("Nested selection is unsupported")
                    }

                    is DeclarativeBinding.Intent -> {
                        host.launch((request as IntentRequest).copy(unlockFirst = capability.execution.requiresUnlock))
                    }

                    is DeclarativeBinding.Content -> {
                        BindingResults.content(
                            binding,
                            host.query(request as ContentRequest, wait.effectiveMillis),
                        )
                    }

                    is DeclarativeBinding.Http -> {
                        val operation = binding.operation
                        if (operation != null) {
                            durable(binding, operation, request as HttpRequest, proposal, wait)
                        } else {
                            BindingResults.http(
                                capability.copy(binding = binding),
                                host.request(request as HttpRequest, wait.effectiveMillis),
                                proposal.arguments,
                            )
                        }
                    }
                }
            } catch (failure: ContentQueryFailure) {
                failure.outcome
            } catch (notSubmitted: BindingNotSubmitted) {
                ExecutionOutcome(InvocationStatus.NOT_EXECUTED, notSubmitted.message.orEmpty())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (binding is DeclarativeBinding.Content) {
                    ExecutionOutcome(InvocationStatus.FAILED, "The content provider returned an invalid result. No rows were returned.")
                } else {
                    ExecutionOutcome(InvocationStatus.UNKNOWN, CapabilityDispatcher.UNKNOWN_MESSAGE)
                }
            }
        return outcome.copy(message = "${outcome.message}\n${wait.receipt()}")
    }

    /**
     * One durable operation. The key names it across re-deliveries of the same invocation; a lost answer
     * is resolved by reading the key back, never by submitting again; and the outcome is the server's own
     * state, read until it is final or the wait runs out.
     */
    private suspend fun durable(
        binding: DeclarativeBinding.Http,
        operation: DurableOperation,
        request: HttpRequest,
        proposal: ToolProposal,
        wait: WaitBudget,
    ): ExecutionOutcome {
        val key = operationKey(proposal)
        val status =
            HttpRequest(
                binding.origin,
                binding.origin + operation.statusPath.replace("{operation}", key),
                "GET",
                null,
                binding.credential,
                binding.maxResponseBytes,
                binding.credentialScheme,
            )
        val window = (wait.effectiveMillis - SETTLE_MILLIS).coerceAtLeast(pollMillis)

        suspend fun lookup(): JsonObject? {
            val response = host.request(status, window)
            if (response.status == 404) return null
            check(response.status in 200..299) { "Status lookup failed" }
            return BoundedJson.parse(response.body, binding.maxResponseBytes) as? JsonObject ?: error("Status is not an object")
        }

        suspend fun afterLostAnswer(): JsonObject =
            try {
                lookup()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw OperationUnknown()
            } ?: throw OperationNotHeld()

        var record =
            try {
                val response = host.request(request.copy(headers = request.headers + (operation.header to key)), window)
                when {
                    response.status in binding.result.notExecutedStatuses -> {
                        return ExecutionOutcome(
                            InvocationStatus.NOT_EXECUTED,
                            "The server refused the operation before performing it (HTTP ${response.status}).",
                        )
                    }

                    response.status == 409 -> {
                        return ExecutionOutcome(
                            InvocationStatus.NOT_EXECUTED,
                            "The server already holds a different operation for this request. Nothing new was submitted.",
                        )
                    }

                    response.status in 200..299 -> {
                        runCatching { BoundedJson.parse(response.body, binding.maxResponseBytes) as? JsonObject }.getOrNull()
                            ?: afterLostAnswer()
                    }

                    else -> {
                        afterLostAnswer()
                    }
                }
            } catch (notSubmitted: BindingNotSubmitted) {
                throw notSubmitted
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: OperationNotHeld) {
                return ExecutionOutcome(
                    InvocationStatus.NOT_EXECUTED,
                    "The server did not accept the operation and holds nothing for this request. Nothing was performed.",
                )
            } catch (_: OperationUnknown) {
                return ExecutionOutcome(InvocationStatus.UNKNOWN, UNKNOWN_SUBMISSION)
            } catch (_: Exception) {
                try {
                    afterLostAnswer()
                } catch (_: OperationNotHeld) {
                    return ExecutionOutcome(
                        InvocationStatus.NOT_EXECUTED,
                        "The server did not answer and holds nothing for this request. Nothing was performed.",
                    )
                } catch (_: OperationUnknown) {
                    return ExecutionOutcome(InvocationStatus.UNKNOWN, UNKNOWN_SUBMISSION)
                }
            }
        var polls = window / pollMillis
        while (outcome(operation, record) == OperationOutcome.PENDING && polls-- > 0) {
            delay(pollMillis)
            record =
                try {
                    lookup()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } ?: record
        }
        return describe(binding, operation, record)
    }

    private fun describe(
        binding: DeclarativeBinding.Http,
        operation: DurableOperation,
        record: JsonObject,
    ): ExecutionOutcome {
        val state = (BindingResults.pointer(record, operation.state) as? JsonPrimitive)?.takeIf { it.isString }?.content
        val detail =
            operation.detail
                ?.let { BindingResults.pointer(record, it) as? JsonPrimitive }
                ?.takeIf { it.isString && it.content.isNotBlank() }
                ?.let { ": " + JsonPrimitive(it.content.take(300)) }
                .orEmpty()
        val named = state?.let { JsonPrimitive(it).toString() } ?: "missing"
        val result =
            BindingResults
                .pointer(record, binding.result.pointer)
                ?.let { value -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: value.toString() }
                ?.take(binding.result.maxBytes)
                ?.let { " Result: $it" }
                .orEmpty()
        val data = BindingResults.boundedData(record)
        return when (outcome(operation, record)) {
            OperationOutcome.COMPLETED -> {
                ExecutionOutcome(
                    InvocationStatus.COMPLETED,
                    (capability.receipts.success ?: "The server completed the operation.") + " Server state: $named.$result",
                    data,
                )
            }

            OperationOutcome.NOT_EXECUTED -> {
                ExecutionOutcome(
                    InvocationStatus.NOT_EXECUTED,
                    "The server did not perform the operation (state $named$detail). It will not be submitted again.",
                    data,
                )
            }

            OperationOutcome.PENDING -> {
                ExecutionOutcome(
                    InvocationStatus.HANDED_OFF,
                    "The server queued the operation (state $named) and had not finished it within the wait. " +
                        "It may still complete; check its result later rather than repeating it.",
                    data,
                )
            }

            OperationOutcome.UNKNOWN -> {
                ExecutionOutcome(
                    InvocationStatus.UNKNOWN,
                    "The operation's outcome is unknown (state $named$detail). EVA does not repeat it; check before trying again.",
                    data,
                )
            }
        }
    }

    private fun outcome(
        operation: DurableOperation,
        record: JsonObject,
    ): OperationOutcome {
        val state = (BindingResults.pointer(record, operation.state) as? JsonPrimitive)?.takeIf { it.isString }?.content
        return state?.let(operation.outcomes::get) ?: OperationOutcome.UNKNOWN
    }

    private class OperationNotHeld : Exception()

    private class OperationUnknown : Exception()

    companion object {
        /** Time kept back from the wait so the outcome is reported before the wait itself expires. */
        const val SETTLE_MILLIS = 2_000L
        const val UNKNOWN_SUBMISSION =
            "The server did not answer, and whether it accepted the operation is unknown. EVA does not repeat it; check before trying again."

        /** Stable for one invocation, distinct for any other; 64 hex characters suit common key formats. */
        fun operationKey(proposal: ToolProposal): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest("${proposal.callId}\u0000${proposal.fingerprint()}".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

class ContentQueryFailure(
    val outcome: ExecutionOutcome,
) : IllegalStateException(outcome.message)
