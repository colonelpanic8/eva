package com.colonelpanic.eva.capability

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** App-owned work survives a disconnected conversation; late replies have a separate routing seam. */
class BoundedExecution(
    private val scope: CoroutineScope,
    private val onLateResult: (ToolProposal, ExecutionOutcome) -> Unit = { _, _ -> },
) {
    private val active = mutableSetOf<String>()

    suspend fun execute(
        instance: String,
        proposal: ToolProposal,
        budget: WaitBudget,
        operation: suspend (ToolProposal) -> ExecutionOutcome,
    ): ExecutionOutcome {
        val admitted =
            synchronized(active) {
                if (active.size >= 4 || instance in active) false else active.add(instance)
            }
        if (!admitted) {
            return ExecutionOutcome(
                InvocationStatus.NOT_EXECUTED,
                "This extension is busy, or EVA has four actions in progress. Nothing was submitted. ${budget.receipt()}",
            )
        }
        val work =
            scope.async {
                try {
                    operation(proposal.copy(waitBudget = budget))
                } finally {
                    synchronized(active) { active.remove(instance) }
                }
            }
        return coroutineScope {
            val cue =
                launch {
                    if (budget.mode == InteractionMode.VOICE) {
                        delay(budget.effectiveMillis / 2)
                        proposal.onWaiting()
                    }
                }
            try {
                val result = withTimeoutOrNull(budget.effectiveMillis) { work.await() }
                if (result != null) {
                    result
                } else {
                    scope.launch {
                        runCatching { work.await() }.getOrNull()?.let { onLateResult(proposal, it) }
                    }
                    ExecutionOutcome(
                        InvocationStatus.UNKNOWN,
                        "The wait expired; the action may still finish. Check the other app before retrying. ${budget.receipt()}",
                    )
                }
            } finally {
                cue.cancelAndJoin()
            }
        }
    }
}

class BudgetedBackend(
    private val instance: String,
    private val backend: ExecutionBackend,
    private val execution: BoundedExecution,
    private val budget: (ToolProposal) -> WaitBudget,
) : ExecutionBackend {
    override fun prepare(proposal: ToolProposal): ToolProposal = proposal.copy(waitBudget = budget(proposal))

    override fun dispatchRejection(): String? = backend.dispatchRejection()

    override suspend fun unavailableReason(): String? = backend.unavailableReason()

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome =
        ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "A journaled invocation is required.")

    override suspend fun execute(proposal: ToolProposal): ExecutionOutcome =
        execution.execute(instance, proposal, proposal.waitBudget ?: budget(proposal), backend::execute)
}
