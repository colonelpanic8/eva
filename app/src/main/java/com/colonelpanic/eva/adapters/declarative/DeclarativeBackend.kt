package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.capability.WaitBudget
import kotlinx.coroutines.CancellationException

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
            arguments = BindingArguments(capability, proposal.arguments)
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
                        host.launch(request as IntentRequest)
                    }

                    is DeclarativeBinding.Content -> {
                        BindingResults.content(
                            binding,
                            host.query(request as ContentRequest, wait.effectiveMillis),
                        )
                    }

                    is DeclarativeBinding.Http -> {
                        BindingResults.http(
                            capability.copy(binding = binding),
                            host.request(request as HttpRequest, wait.effectiveMillis),
                            proposal.arguments,
                        )
                    }
                }
            } catch (notSubmitted: BindingNotSubmitted) {
                ExecutionOutcome(InvocationStatus.NOT_EXECUTED, notSubmitted.message.orEmpty())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ExecutionOutcome(InvocationStatus.UNKNOWN, CapabilityDispatcher.UNKNOWN_MESSAGE)
            }
        return outcome.copy(message = "${outcome.message}\n${wait.receipt()}")
    }
}
