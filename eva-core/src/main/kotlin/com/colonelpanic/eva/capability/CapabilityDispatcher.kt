package com.colonelpanic.eva.capability

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections

class CapabilityDispatcher(
    private val registry: CapabilityRegistry,
    private val repository: InvocationRepository,
    private val executeAdmitted: suspend (
        ToolProposal,
        ExecutionBackend,
    ) -> ExecutionOutcome = { proposal, backend -> backend.execute(proposal) },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** Sees what a backend threw; the journal and the model only learn that the outcome is unknown. */
    private val onBackendFailure: (String, Exception) -> Unit = { _, _ -> },
) {
    private val submissions = CallLocks()
    private val outcomeLocks = CallLocks()
    private val abandoned =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    suspend fun abandon(
        callIds: Collection<String>,
        message: String,
    ) {
        callIds.forEach { id ->
            outcomeLocks.withLock(id) {
                val record = repository.byCallIds(listOf(id))[id]
                if (record?.status in setOf(InvocationStatus.CLAIMED, InvocationStatus.DISPATCHING)) {
                    repository.transition(id, record!!.status, InvocationStatus.UNKNOWN, message)
                    abandoned += id
                }
            }
        }
    }

    private suspend fun transition(
        callId: String,
        expected: InvocationStatus,
        status: InvocationStatus,
        message: String,
        data: kotlinx.serialization.json.JsonObject? = null,
    ): InvocationRecord =
        outcomeLocks.withLock(callId) {
            val prior = if (callId in abandoned) InvocationStatus.UNKNOWN else expected
            repository.transition(callId, prior, status, message, data).also { abandoned -= callId }
        }

    val catalogRevision: String get() = registry.snapshot.revision

    suspend fun execute(
        proposal: ToolProposal,
        rejection: String? = null,
    ): InvocationRecord =
        submissions.withLock(proposal.callId) {
            var snapshot = proposal.copy(arguments = Collections.unmodifiableMap(proposal.arguments.toMap()))
            if (proposal.callId.isBlank() || proposal.callId.length > 256 || proposal.request.length > 1000) {
                throw ProposalRejectedException("The request has invalid metadata. No app was opened.")
            }
            val catalog = registry.snapshot
            val validationError = rejection ?: catalog.validationError(snapshot)
            val definition = catalog.takeIf { it.revision == proposal.catalogRevision }?.definitions?.get(proposal.capabilityId)
            if (validationError == null) snapshot = checkNotNull(catalog.resolve(snapshot)).prepare(snapshot)
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
                    title = definition?.title,
                    arguments = snapshot.arguments,
                    provenance =
                        (definition?.source ?: catalog.resolve(snapshot)?.receiptSource())?.let {
                            ReceiptProvenance(
                                it,
                                catalog.bindingRevisions.getValue(proposal.capabilityId),
                                snapshot.waitBudget,
                            )
                        },
                    threadId = proposal.threadId,
                    turnId = proposal.turnId,
                    initiator = proposal.initiator,
                )
            val claim = journal { repository.claim(initial) }
            if (claim.record.fingerprint != initial.fingerprint) throw ConflictingCallException()
            if (!claim.isNew || validationError != null) return@withLock claim.record

            var phase = InvocationStatus.CLAIMED
            try {
                currentCoroutineContext().ensureActive()
                val backend = checkNotNull(catalog.resolve(snapshot))
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
                        transition(proposal.callId, phase, InvocationStatus.NOT_EXECUTED, unavailable)
                    }
                }
                var admissionRejection = CapabilityRegistry.STALE_MESSAGE
                val admitted =
                    registry.commitDispatch(snapshot, onRejected = { admissionRejection = it }) {
                        journal {
                            transition(proposal.callId, phase, InvocationStatus.DISPATCHING, "Dispatching action…")
                            phase = InvocationStatus.DISPATCHING
                        }
                    }
                if (admitted == null) {
                    return@withLock journal {
                        transition(proposal.callId, phase, InvocationStatus.NOT_EXECUTED, admissionRejection)
                    }
                }
                currentCoroutineContext().ensureActive()
                val outcome =
                    try {
                        executeAdmitted(snapshot, admitted)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        onBackendFailure(proposal.capabilityId, error)
                        ExecutionOutcome(InvocationStatus.UNKNOWN, UNKNOWN_MESSAGE)
                    }
                journal(mayHaveExecuted = true) {
                    transition(proposal.callId, phase, outcome.status, outcome.message, outcome.data)
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
                    transition(proposal.callId, phase, status, message)
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

private class CallLocks {
    private data class Entry(
        val mutex: Mutex = Mutex(),
        var users: Int = 0,
    )

    private val locks = mutableMapOf<String, Entry>()

    suspend fun <T> withLock(
        id: String,
        action: suspend () -> T,
    ): T {
        val entry = synchronized(locks) { locks.getOrPut(id) { Entry() }.also { it.users++ } }
        try {
            return entry.mutex.withLock { action() }
        } finally {
            synchronized(locks) {
                if (--entry.users == 0) locks.remove(id)
            }
        }
    }
}
