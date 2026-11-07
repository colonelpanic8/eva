package com.colonelpanic.eva.capability

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * App-owned work survives a disconnected conversation; late replies have a separate routing seam.
 * One action runs per instance and four at once; a call waits its turn within its own wait budget
 * and is refused, unsubmitted, only if the budget runs out first.
 */
class BoundedExecution(
    private val scope: CoroutineScope,
    private val onLateResult: (ToolProposal, ExecutionOutcome) -> Unit = { _, _ -> },
) {
    private val instances = ConcurrentHashMap<String, Mutex>()
    private val slots = Semaphore(MAX_ACTIVE)

    suspend fun execute(
        instance: String,
        proposal: ToolProposal,
        budget: WaitBudget,
        operation: suspend (ToolProposal) -> ExecutionOutcome,
    ): ExecutionOutcome {
        val turn = instances.getOrPut(instance) { Mutex() }
        var work: Deferred<ExecutionOutcome>? = null
        return coroutineScope {
            val cue =
                launch {
                    if (budget.mode == InteractionMode.VOICE) {
                        delay(budget.effectiveMillis / 2)
                        proposal.onWaiting()
                    }
                }
            try {
                val result =
                    withTimeoutOrNull(budget.effectiveMillis) {
                        turn.lock()
                        try {
                            slots.acquire()
                        } catch (error: Throwable) {
                            turn.unlock()
                            throw error
                        }
                        val admitted =
                            scope.async(start = CoroutineStart.ATOMIC) {
                                try {
                                    operation(proposal.copy(waitBudget = budget))
                                } finally {
                                    slots.release()
                                    turn.unlock()
                                }
                            }
                        work = admitted
                        admitted.await()
                    }
                val submitted = work
                if (result != null) {
                    result
                } else if (submitted == null) {
                    ExecutionOutcome(
                        InvocationStatus.NOT_EXECUTED,
                        "This extension, or EVA's four concurrent actions, stayed busy for the whole wait. " +
                            "Nothing was submitted. ${budget.receipt()}",
                    )
                } else {
                    scope.launch {
                        runCatching { submitted.await() }.getOrNull()?.let { onLateResult(proposal, it) }
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

private const val MAX_ACTIVE = 4

class BudgetedBackend(
    private val instance: String,
    private val backend: ExecutionBackend,
    private val execution: BoundedExecution,
    private val budget: (ToolProposal) -> WaitBudget,
) : ExecutionBackend {
    override fun usesDeviceUi(proposal: ToolProposal): Boolean = backend.usesDeviceUi(proposal)

    override fun prepare(proposal: ToolProposal): ToolProposal = proposal.copy(waitBudget = budget(proposal))

    override fun dispatchRejection(): String? = backend.dispatchRejection()

    override suspend fun unavailableReason(): String? = backend.unavailableReason()

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome =
        ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "A journaled invocation is required.")

    override suspend fun execute(proposal: ToolProposal): ExecutionOutcome =
        execution.execute(instance, proposal, proposal.waitBudget ?: budget(proposal), backend::execute)
}
