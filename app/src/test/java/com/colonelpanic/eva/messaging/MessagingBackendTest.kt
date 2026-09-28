package com.colonelpanic.eva.messaging

import com.colonelpanic.eva.adapters.declarative.BearerCredential
import com.colonelpanic.eva.adapters.declarative.ContentRequest
import com.colonelpanic.eva.adapters.declarative.ContentRows
import com.colonelpanic.eva.adapters.declarative.DeclarativeBackend
import com.colonelpanic.eva.adapters.declarative.DeclarativeBinding
import com.colonelpanic.eva.adapters.declarative.DeclarativeHost
import com.colonelpanic.eva.adapters.declarative.HttpRequest
import com.colonelpanic.eva.adapters.declarative.HttpResponse
import com.colonelpanic.eva.adapters.declarative.IntentRequest
import com.colonelpanic.eva.adapters.declarative.PackageCodec
import com.colonelpanic.eva.adapters.declarative.PackageHttpClient
import com.colonelpanic.eva.adapters.declarative.PackageMessagingService
import com.colonelpanic.eva.capability.BundledCapabilities
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ClaimResult
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InteractionMode
import com.colonelpanic.eva.capability.InvocationRecord
import com.colonelpanic.eva.capability.InvocationRepository
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.capability.WaitBudget
import com.colonelpanic.eva.data.effectiveSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
    private val bridgePackage =
        PackageCodec.decode(
            generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
                .map { File(it, "docs/examples/messaging-bridge.json") }
                .first { it.isFile }
                .readText(),
        )
    private val role = checkNotNull(bridgePackage.messaging)
    private var granted = role.tools
    private val http = PackageHttpClient({ origin, _ -> BearerCredential.create(origin, FakeBridge.TOKEN) }, fake.client)
    private val host =
        object : DeclarativeHost {
            override suspend fun unavailableReason(binding: DeclarativeBinding): String? = null

            override suspend fun launch(request: IntentRequest): ExecutionOutcome = error("unused")

            override suspend fun query(
                request: ContentRequest,
                timeoutMillis: Long,
            ): ContentRows = error("unused")

            override suspend fun request(
                request: HttpRequest,
                timeoutMillis: Long,
            ): HttpResponse = http.execute(request, timeoutMillis)
        }
    private val services =
        object : MessagingServices {
            override fun all() =
                listOf(
                    PackageMessagingService(
                        "whatsapp",
                        "WhatsApp",
                        "p",
                        "Messaging bridge",
                        role,
                        role.tools.associateWith { "extension.package.p.$it" },
                    ),
                )

            override fun backend(capabilityId: String): ExecutionBackend? {
                val name = capabilityId.removePrefix("extension.package.p.")
                if (name !in granted) return null
                return DeclarativeBackend(
                    bridgePackage.capabilities.single { it.name == name },
                    host,
                    { effectiveSettings(bridgePackage, emptyMap()) },
                    pollMillis = 10,
                ) { WaitBudget(InteractionMode.TYPED, 5_000, null, null) }
            }
        }

    private fun withService(operation: MessagingBackend.Operation) = MessagingBackend(operation, sms, notifications, services)

    private val alice = """{"id":"alice@s","name":"Alice","address":"+14155550100","is_me":false}"""
    private val chat =
        """{"schema":1,"id":"chat-1","name":"","preview":"hey","updated":"2026-09-26T09:00:00Z","unread":true,"read_only":false,
        "protocol":"whatsapp","state":"active","participants":[$alice]}"""

    private suspend fun dispatch(
        operation: MessagingBackend.Operation,
        id: String,
        arguments: Map<String, String>,
        callId: String = "call-1",
    ): InvocationRecord {
        val definition = BundledCapabilities.definitions.single { it.id == id }
        val registry = CapabilityRegistry(mapOf(id to withService(operation)), listOf(definition))
        return CapabilityDispatcher(registry, MemoryInvocationRepository()).execute(
            ToolProposal(callId, id, arguments, "request", registry.snapshot.revision),
        )
    }

    @Test
    fun `a service an extension provides answers the shared tools while sms and notifications keep their paths`() =
        runTest {
            fake.conversations = "[$chat]"
            fake.contacts = """[{"id":"c1","name":"Alicia","address":"+14155550111"}]"""
            val found =
                dispatch(
                    MessagingBackend.Operation.SEARCH,
                    CapabilityRegistry.CONVERSATIONS_SEARCH,
                    mapOf(
                        "service" to "WhatsApp",
                        "query" to "Ali",
                    ),
                )
            assertEquals(InvocationStatus.COMPLETED, found.status)
            assertTrue(found.message, found.message.contains("conversationRef \"chat-1\" | \"\" | people [\"Alice\"] [\"+14155550100\"]"))
            assertTrue(found.message, found.message.contains("\"Alicia\" | number \"+14155550111\""))
            assertTrue(found.message.contains("Pass service whatsapp"))
            assertEquals(mapOf("q" to "Ali", "limit" to "10"), fake.requests.first { it.path == "/v1/conversations" }.query)
            val read =
                dispatch(
                    MessagingBackend.Operation.READ,
                    CapabilityRegistry.CONVERSATION_READ,
                    mapOf("service" to "whatsapp", "conversationRef" to "chat-1", "limit" to "5"),
                )
            assertEquals(InvocationStatus.COMPLETED, read.status)
            assertEquals(mapOf("limit" to "5"), fake.requests.last().query)
            assertEquals("/v1/conversations/chat-1/messages", fake.requests.last().path)
            val sms = withService(MessagingBackend.Operation.SEND).execute(mapOf("recipient" to "+15551234567", "message" to "Hi"))
            assertEquals(InvocationStatus.COMPLETED, sms.status)
            assertEquals(1, sends)
            val app = withService(MessagingBackend.Operation.SEARCH).execute(mapOf("service" to "Signal", "query" to "Ali"))
            assertTrue(app.message.contains("Active notification conversations only"))
            val smsId =
                dispatch(
                    MessagingBackend.Operation.SEND,
                    CapabilityRegistry.SMS_SEND,
                    mapOf("service" to "whatsapp", "conversationId" to "7", "message" to "Hi"),
                )
            assertEquals(InvocationStatus.NOT_EXECUTED, smsId.status)
            assertTrue(fake.requests.none { it.method == "POST" })
        }

    @Test
    fun `a redelivered send reaches the same server operation and the token never reaches the journal`() =
        runTest {
            fake.onQueue = { _, key, _ ->
                (if (key in fake.outbox) 200 else 202) to
                    """{"id":"$key","state":"accepted","conversation_id":"chat-1","message_id":"m-1"}""".also { fake.outbox[key] = it }
            }
            val arguments = mapOf("service" to "whatsapp", "conversationRef" to "chat-1", "message" to "Hi")
            val first = dispatch(MessagingBackend.Operation.SEND, CapabilityRegistry.SMS_SEND, arguments)
            val again = dispatch(MessagingBackend.Operation.SEND, CapabilityRegistry.SMS_SEND, arguments)
            assertEquals(InvocationStatus.COMPLETED, first.status)
            assertTrue(first.message, first.message.contains("not a delivery or read receipt"))
            assertEquals(InvocationStatus.COMPLETED, again.status)
            val keys = fake.requests.filter { it.method == "POST" }.map { it.headers.getValue("Idempotency-Key") }
            assertEquals(2, keys.size)
            assertEquals(keys[0], keys[1])
            assertEquals(1, fake.outbox.size)
            assertEquals(
                InvocationStatus.COMPLETED,
                dispatch(MessagingBackend.Operation.SEND, CapabilityRegistry.SMS_SEND, arguments, "call-2").status,
            )
            assertEquals(2, fake.outbox.size)
            listOf(first, again).forEach { assertFalse(it.toString().contains(FakeBridge.TOKEN)) }
        }

    @Test
    fun `a number starts the chat before the message is queued and a refused chat sends nothing`() =
        runTest {
            fake.onQueue = { path, key, _ ->
                val record =
                    if (path == "/v1/conversations") {
                        """{"id":"$key","state":"accepted","conversation_id":"bob@s"}"""
                    } else {
                        """{"id":"$key","state":"accepted","conversation_id":"bob@s","message_id":"m-2"}"""
                    }
                fake.outbox[key] = record
                202 to record
            }
            val sent =
                dispatch(
                    MessagingBackend.Operation.SEND,
                    CapabilityRegistry.SMS_SEND,
                    mapOf("service" to "whatsapp", "recipient" to "+14155550122", "message" to "Hello Bob"),
                )
            assertEquals(InvocationStatus.COMPLETED, sent.status)
            val posts = fake.requests.filter { it.method == "POST" }
            assertEquals(listOf("/v1/conversations", "/v1/messages"), posts.map { it.path })
            assertEquals("""{"recipients":["+14155550122"]}""", posts[0].body)
            assertEquals("""{"conversation_id":"bob@s","text":"Hello Bob"}""", posts[1].body)
            assertTrue(posts[0].headers.getValue("Idempotency-Key") != posts[1].headers.getValue("Idempotency-Key"))
            fake.requests.clear()
            fake.onQueue = { _, key, _ ->
                202 to """{"id":"$key","state":"rejected","detail":"+14155550133 is not on WhatsApp"}""".also { fake.outbox[key] = it }
            }
            val refused =
                dispatch(
                    MessagingBackend.Operation.SEND,
                    CapabilityRegistry.SMS_SEND,
                    mapOf("service" to "whatsapp", "recipient" to "+14155550133", "message" to "Hi"),
                    "call-3",
                )
            assertEquals(InvocationStatus.NOT_EXECUTED, refused.status)
            assertTrue(refused.message, refused.message.contains("is not on WhatsApp") && refused.message.contains("was not sent"))
            assertEquals(listOf("/v1/conversations"), fake.requests.filter { it.method == "POST" }.map { it.path })
            val local =
                dispatch(
                    MessagingBackend.Operation.SEND,
                    CapabilityRegistry.SMS_SEND,
                    mapOf("service" to "whatsapp", "recipient" to "415 555 0100", "message" to "Hi"),
                    "call-4",
                )
            assertEquals(InvocationStatus.NOT_EXECUTED, local.status)
            assertTrue(local.message.contains("starting with +"))
        }

    @Test
    fun `an extension without its send grant cannot send through the shared tool`() =
        runTest {
            granted = role.tools - "send"
            val refused =
                dispatch(
                    MessagingBackend.Operation.SEND,
                    CapabilityRegistry.SMS_SEND,
                    mapOf(
                        "service" to "whatsapp",
                        "conversationRef" to "chat-1",
                        "message" to "Hi",
                    ),
                )
            assertEquals(InvocationStatus.NOT_EXECUTED, refused.status)
            assertTrue(refused.message, refused.message.contains("allow its send action"))
            assertTrue(fake.requests.isEmpty())
            assertEquals(0, sends)
        }
}
