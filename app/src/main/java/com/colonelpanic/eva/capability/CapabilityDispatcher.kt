package com.colonelpanic.eva.capability

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class CapabilityDispatcher(
    private val registry: CapabilityRegistry,
    private val repository: InvocationRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val submissions = Mutex()

    suspend fun execute(
        proposal: ToolProposal,
        rejection: String? = null,
    ): InvocationRecord =
        submissions.withLock {
            val snapshot = proposal.copy(arguments = proposal.arguments.toMap())
            if (proposal.callId.isBlank() || proposal.callId.length > 256 || proposal.request.length > 1000) {
                throw ProposalRejectedException("The request has invalid metadata. No app was opened.")
            }
            val validationError = rejection ?: registry.validationError(snapshot)
            val initial =
                InvocationRecord(
                    callId = proposal.callId,
                    fingerprint = snapshot.fingerprint(),
                    request = proposal.request,
                    destination = snapshot.arguments["destination"],
                    status = if (validationError == null) InvocationStatus.CLAIMED else InvocationStatus.NOT_EXECUTED,
                    message = validationError ?: "Preparing action…",
                    createdAtMillis = nowMillis(),
                    capabilityId = proposal.capabilityId,
                    catalogRevision = proposal.catalogRevision,
                    title = registry.catalog.find { it.id == proposal.capabilityId }?.title,
                    threadId = proposal.threadId,
                    turnId = proposal.turnId,
                )
            val claim = journal { repository.claim(initial) }
            if (claim.record.fingerprint != initial.fingerprint) throw ConflictingCallException()
            if (!claim.isNew || validationError != null) return@withLock claim.record

            var phase = InvocationStatus.CLAIMED
            try {
                currentCoroutineContext().ensureActive()
                val backend = checkNotNull(registry.resolve(snapshot))
                val unavailable =
                    try {
                        backend.unavailableReason()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        "This action is temporarily unavailable. No app was opened."
                    }
                if (unavailable != null) {
                    return@withLock journal {
                        repository.transition(proposal.callId, phase, InvocationStatus.NOT_EXECUTED, unavailable)
                    }
                }
                journal {
                    repository.transition(proposal.callId, phase, InvocationStatus.DISPATCHING, "Opening app…")
                    phase = InvocationStatus.DISPATCHING
                }
                currentCoroutineContext().ensureActive()
                val outcome =
                    try {
                        backend.execute(snapshot.arguments)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        ExecutionOutcome(InvocationStatus.UNKNOWN, UNKNOWN_MESSAGE)
                    }
                journal(mayHaveExecuted = true) {
                    repository.transition(proposal.callId, phase, outcome.status, outcome.message)
                }
            } catch (error: CancellationException) {
                journal(mayHaveExecuted = phase == InvocationStatus.DISPATCHING) {
                    val status = if (phase == InvocationStatus.CLAIMED) InvocationStatus.NOT_EXECUTED else InvocationStatus.UNKNOWN
                    val message =
                        if (phase ==
                            InvocationStatus.CLAIMED
                        ) {
                            "The request was canceled before dispatch. No app was opened."
                        } else {
                            UNKNOWN_MESSAGE
                        }
                    repository.transition(proposal.callId, phase, status, message)
                }
                throw error
            }
        }

    private suspend fun <T> journal(
        mayHaveExecuted: Boolean = false,
        operation: suspend () -> T,
    ): T =
        try {
            withContext(NonCancellable) { operation() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw InvocationPersistenceException(mayHaveExecuted, error)
        }

    companion object {
        const val UNKNOWN_MESSAGE = "The action outcome is unknown. Check the other app before trying again."
    }
}
