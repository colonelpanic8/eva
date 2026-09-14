package com.colonelpanic.eva.capability

import java.security.MessageDigest

data class ToolProposal(
    val callId: String,
    val capabilityId: String,
    val arguments: Map<String, String>,
    val request: String,
    val catalogRevision: String,
    val threadId: String? = null,
    val turnId: String? = null,
) {
    fun fingerprint(): String {
        val fields = listOf(capabilityId, catalogRevision) + arguments.toSortedMap().flatMap { listOf(it.key, it.value) }
        val encoded = fields.joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

enum class InvocationStatus {
    CLAIMED,
    DISPATCHING,
    HANDED_OFF,
    COMPLETED,
    NOT_EXECUTED,
    FAILED,
    UNKNOWN,
}

data class InvocationRecord(
    val callId: String,
    val fingerprint: String,
    val request: String,
    val destination: String?,
    val status: InvocationStatus,
    val message: String,
    val createdAtMillis: Long,
    val capabilityId: String,
    val catalogRevision: String,
    val title: String? = null,
    /** Null for receipts journaled before threads existed. */
    val threadId: String? = null,
    val turnId: String? = null,
)

data class ClaimResult(
    val record: InvocationRecord,
    val isNew: Boolean,
)

interface InvocationRepository {
    suspend fun recoverInterrupted()

    suspend fun claim(record: InvocationRecord): ClaimResult

    suspend fun transition(
        callId: String,
        expected: InvocationStatus,
        status: InvocationStatus,
        message: String,
    ): InvocationRecord

    suspend fun history(): List<InvocationRecord>
}

data class ExecutionOutcome(
    val status: InvocationStatus,
    val message: String,
) {
    init {
        require(status != InvocationStatus.CLAIMED && status != InvocationStatus.DISPATCHING)
    }
}

interface ExecutionBackend {
    suspend fun unavailableReason(): String?

    suspend fun execute(arguments: Map<String, String>): ExecutionOutcome
}

open class ProposalRejectedException(
    message: String,
) : IllegalArgumentException(message)

class ConflictingCallException : ProposalRejectedException("A request ID was reused with different arguments. No new action was sent.")

class InvocationPersistenceException(
    val mayHaveExecuted: Boolean,
    cause: Exception,
) : IllegalStateException("Action history could not be saved.", cause)
