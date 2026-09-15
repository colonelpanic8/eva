package com.colonelpanic.eva.messaging

import com.colonelpanic.eva.capability.BundledCapabilities
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ClaimResult
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationRecord
import com.colonelpanic.eva.capability.InvocationRepository
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.capability.ToolProposal
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingBackendTest {
    private var sends = 0
    private val sms =
        object : ExecutionBackend {
            override suspend fun unavailableReason(): String? = null

            override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                sends++
                return ExecutionOutcome(InvocationStatus.COMPLETED, "SMS sent")
            }
        }
    private val notifications = NotificationMessages({ true }, { false }, { 0L })
    private val backend = MessagingBackend(MessagingBackend.Operation.SEND, sms, notifications)

    @Test
    fun revokedGrantAfterClaimPreventsDurableDispatch() =
        runTest {
            var allowed = true
            var replies = 0
            val messages = NotificationMessages({ true }, { allowed }, { 0L })
            messages.publish("key", MessagingApp("identity", "example.chat", "Chat"), "Alice", "Hi") {
                replies++
                ExecutionOutcome(InvocationStatus.HANDED_OFF, "Sent")
            }
            val encoded = messages.search("notifications", null, 1).message.substringAfter("External app data: ")
            val row = Json.parseToJsonElement(encoded) as JsonArray
            val reference =
                (
                    (row.first() as JsonObject).getValue("conversationRef") as
                        JsonPrimitive
                ).content
            val definition = BundledCapabilities.definitions.single { it.id == CapabilityRegistry.SMS_SEND }
            val backend = MessagingBackend(MessagingBackend.Operation.SEND, sms, messages)
            val registry = CapabilityRegistry(mapOf(definition.id to backend), listOf(definition))
            val memory = MemoryInvocationRepository()
            val repository =
                object : InvocationRepository by memory {
                    override suspend fun claim(record: InvocationRecord): ClaimResult {
                        allowed = false
                        return memory.claim(record)
                    }

                    override suspend fun transition(
                        callId: String,
                        expected: InvocationStatus,
                        status: InvocationStatus,
                        message: String,
                        data: JsonObject?,
                    ): InvocationRecord {
                        assertTrue(status != InvocationStatus.DISPATCHING)
                        return memory.transition(callId, expected, status, message, data)
                    }
                }
            val result =
                CapabilityDispatcher(registry, repository).execute(
                    ToolProposal(
                        "reply",
                        definition.id,
                        mapOf("conversationRef" to reference, "message" to "Hi"),
                        "Reply hi",
                        registry.snapshot.revision,
                    ),
                )
            assertEquals(InvocationStatus.NOT_EXECUTED, result.status)
            assertEquals(0, replies)
        }

    @Test fun explicitAppNeverFallsBackToSms() =
        runTest {
            val result = backend.execute(mapOf("service" to "Chat", "recipient" to "+15551234567", "message" to "Hi"))
            assertEquals(InvocationStatus.NOT_EXECUTED, result.status)
            assertEquals(0, sends)
            assertEquals(InvocationStatus.NOT_EXECUTED, backend.execute(mapOf("conversationRef" to "fake", "message" to "Hi")).status)
            assertEquals(0, sends)
        }

    @Test fun existingSmsArgumentsWorkAndJournalRetainsAttribution() =
        runTest {
            val definition = BundledCapabilities.definitions.single { it.id == CapabilityRegistry.SMS_SEND }
            val registry = CapabilityRegistry(mapOf(definition.id to backend), listOf(definition))
            val repository = MemoryInvocationRepository()
            val result =
                CapabilityDispatcher(registry, repository).execute(
                    ToolProposal(
                        "send",
                        definition.id,
                        mapOf("recipient" to "+15551234567", "message" to "Hi"),
                        "Send hi",
                        registry.snapshot.revision,
                    ),
                )
            assertEquals(InvocationStatus.COMPLETED, result.status)
            assertEquals(1, sends)
            assertEquals("android.messaging", result.provenance?.source?.id)
            assertEquals(result, repository.history().single())
        }
}
