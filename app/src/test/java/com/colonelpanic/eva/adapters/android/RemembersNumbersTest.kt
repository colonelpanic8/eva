package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RemembersNumbersTest {
    private class Fixed(
        private val status: InvocationStatus,
    ) : ExecutionBackend {
        override suspend fun unavailableReason(): String? = null

        override suspend fun execute(arguments: Map<String, String>) = ExecutionOutcome(status, "done")
    }

    @Test
    fun `numbers are remembered only once the action went through`() =
        runTest {
            val recorded = mutableListOf<List<String>>()
            val arguments = mapOf("recipient" to "202-555-0101, 202-555-0102", "message" to "hi")
            RemembersNumbers(Fixed(InvocationStatus.COMPLETED), "recipient") { recorded += it }.execute(arguments)
            RemembersNumbers(Fixed(InvocationStatus.HANDED_OFF), "recipient") { recorded += it }.execute(arguments)
            RemembersNumbers(Fixed(InvocationStatus.FAILED), "recipient") { recorded += it }.execute(arguments)
            RemembersNumbers(Fixed(InvocationStatus.UNKNOWN), "recipient") { recorded += it }.execute(arguments)
            RemembersNumbers(Fixed(InvocationStatus.COMPLETED), "recipient") { recorded += it }
                .execute(mapOf("conversationId" to "4", "message" to "hi"))
            assertEquals(List(2) { listOf("202-555-0101", "202-555-0102") }, recorded)
        }
}
