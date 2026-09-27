package com.colonelpanic.eva.messaging

import com.colonelpanic.eva.adapters.declarative.BearerCredential
import com.colonelpanic.eva.adapters.declarative.PackageHttpClient
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
import com.colonelpanic.eva.data.configuration.MessagingBridgeDefinition
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

    private val fake = FakeBridge()
    private val bridges =
        BridgeMessaging(
            bridges = { mapOf("whatsapp" to MessagingBridgeDefinition("WhatsApp", FakeBridge.ORIGIN)) },
            http = PackageHttpClient({ origin, _ -> BearerCredential.create(origin, FakeBridge.TOKEN) }, fake.client),
            pollMillis = 10,
            sendWaitMillis = 1_000,
        )

    private fun withBridge(operation: MessagingBackend.Operation) = MessagingBackend(operation, sms, notifications, bridges)

    private val chat =
        """{"schema":1,"id":"chat-1","name":"","preview":"hey","updated":"2026-09-26T09:00:00Z","unread":false,"read_only":false,
        "protocol":"whatsapp","state":"active","participants":[{"id":"a","name":"Alice","address":"+14155550100","is_me":false}]}"""

    @Test
    fun `a service naming a bridge routes there while sms and notifications keep their paths`() =
        runTest {
            fake.conversations = "[$chat]"
            val search = withBridge(MessagingBackend.Operation.SEARCH)
            val bridged = search.execute(mapOf("service" to "WhatsApp", "query" to "Ali"))
            assertEquals(InvocationStatus.COMPLETED, bridged.status)
            assertTrue(bridged.message.contains("bridge:whatsapp:chat-1"))
            assertEquals("/v1/conversations", fake.requests.first().path)
            val app = search.execute(mapOf("service" to "Signal", "query" to "Ali"))
            assertEquals(InvocationStatus.COMPLETED, app.status)
            assertTrue(app.message.contains("Active notification conversations only"))
            assertEquals(
                InvocationStatus.COMPLETED,
                withBridge(MessagingBackend.Operation.SEND)
                    .execute(
                        mapOf(
                            "recipient" to "+15551234567",
                            "message" to "Hi",
                        ),
                    ).status,
            )
            assertEquals(1, sends)
            assertEquals(1, fake.requests.count { it.path == "/v1/conversations" })
        }

    @Test
    fun `bridge references route without a service and mismatches are refused rather than sent elsewhere`() =
        runTest {
            fake.conversations = "[$chat]"
            val read = withBridge(MessagingBackend.Operation.READ)
            assertEquals(InvocationStatus.COMPLETED, read.execute(mapOf("conversationRef" to "bridge:whatsapp:chat-1")).status)
            assertTrue(fake.requests.any { it.path == "/v1/conversations/chat-1/messages" })
            val mismatch = read.execute(mapOf("service" to "Signal", "conversationRef" to "bridge:whatsapp:chat-1"))
            assertEquals(InvocationStatus.NOT_EXECUTED, mismatch.status)
            assertTrue(mismatch.message, mismatch.message.contains("belongs to whatsapp, not Signal"))
            val elsewhere =
                withBridge(MessagingBackend.Operation.SEND).execute(
                    mapOf(
                        "conversationRef" to "bridge:telegram:chat-9",
                        "message" to "Hi",
                    ),
                )
            assertEquals(InvocationStatus.NOT_EXECUTED, elsewhere.status)
            assertTrue(elsewhere.message, elsewhere.message.contains("no longer configured"))
            val wrongService =
                withBridge(MessagingBackend.Operation.SEND).execute(
                    mapOf(
                        "service" to "whatsapp",
                        "conversationRef" to "bridge:telegram:chat-9",
                        "message" to "Hi",
                    ),
                )
            assertEquals(InvocationStatus.NOT_EXECUTED, wrongService.status)
            assertTrue(wrongService.message, wrongService.message.contains("belongs to telegram"))
            val smsId =
                withBridge(MessagingBackend.Operation.SEND).execute(
                    mapOf(
                        "service" to "whatsapp",
                        "conversationId" to "7",
                        "message" to "Hi",
                    ),
                )
            assertEquals(InvocationStatus.NOT_EXECUTED, smsId.status)
            val local =
                withBridge(MessagingBackend.Operation.SEND).execute(
                    mapOf(
                        "service" to "whatsapp",
                        "recipient" to "415 555 0100",
                        "message" to "Hi",
                    ),
                )
            assertEquals(InvocationStatus.NOT_EXECUTED, local.status)
            assertTrue(local.message, local.message.contains("starting with +"))
            assertEquals(0, sends)
            assertTrue(fake.requests.none { it.method == "POST" })
        }

    @Test
    fun `a redelivered send derives the same idempotency key and the token never reaches the journal`() =
        runTest {
            fake.conversations = "[$chat]"
            fake.onQueue = { _, key, _ ->
                (if (key in fake.outbox) 200 else 202) to
                    """{"id":"$key","state":"accepted","conversation_id":"chat-1","schema":1}""".also { fake.outbox[key] = it }
            }
            val definition = BundledCapabilities.definitions.single { it.id == CapabilityRegistry.SMS_SEND }
            val registry = CapabilityRegistry(mapOf(definition.id to withBridge(MessagingBackend.Operation.SEND)), listOf(definition))
            val proposal =
                ToolProposal(
                    "send-1",
                    definition.id,
                    mapOf("service" to "whatsapp", "conversationRef" to "bridge:whatsapp:chat-1", "message" to "Hi"),
                    "Message Alice on WhatsApp",
                    registry.snapshot.revision,
                )
            val first = MemoryInvocationRepository()
            val delivered = CapabilityDispatcher(registry, first).execute(proposal)
            assertEquals(InvocationStatus.COMPLETED, delivered.status)
            assertEquals("android.messaging", delivered.provenance?.source?.id)
            // A lost result and a fresh journal replay the same invocation; the bridge sees one operation.
            val redelivered = CapabilityDispatcher(registry, MemoryInvocationRepository()).execute(proposal)
            assertEquals(InvocationStatus.COMPLETED, redelivered.status)
            val keys = fake.requests.filter { it.method == "POST" }.map { it.headers.getValue("Idempotency-Key") }
            assertEquals(2, keys.size)
            assertEquals(keys[0], keys[1])
            assertEquals(1, fake.outbox.size)
            val other = CapabilityDispatcher(registry, MemoryInvocationRepository()).execute(proposal.copy(callId = "send-2"))
            assertEquals(InvocationStatus.COMPLETED, other.status)
            assertEquals(2, fake.outbox.size)
            first.history().forEach { record ->
                assertFalse(record.message.contains(FakeBridge.TOKEN))
                assertFalse(record.arguments.toString().contains(FakeBridge.TOKEN))
            }
        }
}
