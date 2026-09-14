package com.colonelpanic.eva.capability

class MemoryInvocationRepository : InvocationRepository {
    val records = linkedMapOf<String, InvocationRecord>()
    var failClaim = false
    var failDispatch = false
    var failOutcome = false

    override suspend fun recoverInterrupted() {
        records.replaceAll { _, record ->
            when (record.status) {
                InvocationStatus.CLAIMED -> {
                    record.copy(status = InvocationStatus.NOT_EXECUTED, message = "Not sent")
                }

                InvocationStatus.DISPATCHING -> {
                    record.copy(
                        status = InvocationStatus.UNKNOWN,
                        message = CapabilityDispatcher.UNKNOWN_MESSAGE,
                    )
                }

                else -> {
                    record
                }
            }
        }
    }

    override suspend fun claim(record: InvocationRecord): ClaimResult {
        check(!failClaim) { "Storage unavailable" }
        val prior = records.putIfAbsent(record.callId, record)
        return ClaimResult(prior ?: record, prior == null)
    }

    override suspend fun transition(
        callId: String,
        expected: InvocationStatus,
        status: InvocationStatus,
        message: String,
    ): InvocationRecord {
        check(!failDispatch || status != InvocationStatus.DISPATCHING)
        check(!failOutcome || status == InvocationStatus.DISPATCHING)
        val record = checkNotNull(records[callId])
        check(record.status == expected)
        return record.copy(status = status, message = message).also { records[callId] = it }
    }

    override suspend fun history() = records.values.toList()

    override suspend fun byCallIds(ids: Collection<String>) = records.filterKeys { it in ids }
}
