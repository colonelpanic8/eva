package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InteractionMode
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.capability.WaitBudget
import com.colonelpanic.eva.data.effectiveSettings
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessagingBridgePackageTest {
    private val json =
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "docs/examples/messaging-bridge.json") }
            .first { it.isFile }
            .readText()
    private val definition = PackageCodec.decode(json)
    private val send = definition.capabilities.single { it.name == "send" }

    private class ScriptedHost(
        val respond: (HttpRequest) -> HttpResponse,
    ) : DeclarativeHost {
        val requests = mutableListOf<HttpRequest>()

        override suspend fun unavailableReason(binding: DeclarativeBinding): String? = null

        override suspend fun launch(request: IntentRequest): ExecutionOutcome = error("unused")

        override suspend fun query(
            request: ContentRequest,
            timeoutMillis: Long,
        ): ContentRows = error("unused")

        override suspend fun request(
            request: HttpRequest,
            timeoutMillis: Long,
        ): HttpResponse {
            requests += request
            return respond(request)
        }
    }

    private fun backend(host: DeclarativeHost) =
        DeclarativeBackend(send, host, pollMillis = 10) { WaitBudget(InteractionMode.TYPED, 5_000, null, null) }

    private fun proposal(callId: String = "call-1") =
        ToolProposal(callId, "extension.package.p.send", mapOf("conversation" to "chat-1", "text" to "Hi"), "Say hi", "rev")

    private fun record(
        state: String,
        detail: String = "",
    ) = HttpResponse(200, """{"id":"k","state":"$state","message_id":"m-1","detail":"$detail"}""")

    @Test
    fun `the fixture declares settings a messaging role and durable sends`() {
        assertEquals(setOf("service", "label"), definition.settings.keys)
        assertEquals(JsonPrimitive("whatsapp"), definition.settings.getValue("service").default)
        val role = checkNotNull(definition.messaging)
        assertEquals(TextSource.Setting("service"), role.service)
        assertEquals(setOf("conversations", "contacts", "messages", "send", "start_chat"), role.tools)
        val operation = checkNotNull((send.binding as DeclarativeBinding.Http).operation)
        assertEquals("Idempotency-Key", operation.header)
        assertEquals(OperationOutcome.UNKNOWN, operation.outcomes["ambiguous"])
        assertEquals(OperationOutcome.PENDING, operation.outcomes["queued"])
        assertEquals(
            mapOf("service" to JsonPrimitive("signal"), "label" to JsonPrimitive("WhatsApp")),
            effectiveSettings(definition, mapOf("service" to "signal", "label" to "")),
        )
    }

    @Test
    fun `invalid settings roles and operations are rejected when the package is read`() {
        fun rejects(edit: (String) -> String) = assertThrows(IllegalArgumentException::class.java) { PackageCodec.decode(edit(json)) }
        rejects { it.replace("\"service\": {\n      \"setting\": \"service\"\n    }", "\"service\": \"sms\"") }
        rejects { it.replace("\"setting\": \"label\"", "\"setting\": \"missing\"") }
        rejects {
            it.replace(
                "\"type\": \"string\",\n      \"title\": \"Service name\"",
                "\"type\": \"number\",\n      \"title\": \"Service name\"",
            )
        }
        rejects { it.replace("\"default\": \"whatsapp\"", "\"default\": 7") }
        rejects { it.replace("\"send\": {\n      \"tool\": \"send\"", "\"send\": {\n      \"tool\": \"messages\"") }
        rejects { it.replace("\"path\": \"/v1/outbox/{operation}\"", "\"path\": \"/v1/outbox/{key}\"") }
        rejects { it.replace("\"accepted\": \"completed\",", "\"accepted\": \"done\",") }
        rejects { it.replace("\"header\": \"Idempotency-Key\"", "\"header\": \"Authorization\"") }
        rejects {
            it.replace(
                "\"pointer\": \"/participants/*/name\",\n                \"type\": \"stringArray\"",
                "\"pointer\": \"/participants/*/name\",\n                \"type\": \"string\"",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PackageCodec.decode(
                json.replace(
                    "\"method\": \"POST\",\n        \"path\": \"/v1/messages\"",
                    "\"method\": \"GET\",\n        \"path\": \"/v1/messages\"",
                ),
            )
        }
    }

    @Test
    fun `a durable send keys the operation by invocation and reports the server state it reaches`() =
        runTest {
            var reads = 0
            val host =
                ScriptedHost { request ->
                    if (request.method == "POST") {
                        HttpResponse(202, """{"id":"k","state":"queued"}""")
                    } else {
                        if (++reads < 3) record("sending") else record("accepted")
                    }
                }
            val outcome = backend(host).execute(proposal())
            assertEquals(InvocationStatus.COMPLETED, outcome.status)
            assertTrue(outcome.message, outcome.message.startsWith(send.receipts.success!!))
            assertTrue(outcome.message, outcome.message.contains("Server state: \"accepted\". Result: m-1"))
            val key =
                host.requests
                    .first()
                    .headers
                    .getValue("Idempotency-Key")
            assertEquals(DeclarativeBackend.operationKey(proposal()), key)
            assertTrue(Regex("[0-9a-f]{64}").matches(key))
            assertTrue(host.requests.drop(1).all { it.method == "GET" && it.url == "https://bridge.example.ts.net/v1/outbox/$key" })
            assertEquals(1, host.requests.count { it.method == "POST" })
            assertEquals("""{"conversation_id":"chat-1","text":"Hi"}""", host.requests.first().body)
            assertNotEquals(key, DeclarativeBackend.operationKey(proposal("call-2")))
        }

    @Test
    fun `each server state maps to a distinct outcome and nothing is resubmitted`() =
        runTest {
            fun host(state: String) = ScriptedHost { record(state, "not on WhatsApp") }
            listOf(
                "rejected" to InvocationStatus.NOT_EXECUTED,
                "canceled" to InvocationStatus.NOT_EXECUTED,
                "ambiguous" to InvocationStatus.UNKNOWN,
                "confirmed" to InvocationStatus.COMPLETED,
                "exploded" to InvocationStatus.UNKNOWN,
            ).forEach { (state, expected) ->
                val scripted = host(state)
                val outcome = backend(scripted).execute(proposal())
                assertEquals(state, expected, outcome.status)
                assertEquals(1, scripted.requests.count { it.method == "POST" })
                if (expected == InvocationStatus.NOT_EXECUTED) assertTrue(outcome.message.contains("\"not on WhatsApp\""))
            }
            val pending = ScriptedHost { record("queued") }
            val handed = backend(pending).execute(proposal())
            assertEquals(InvocationStatus.HANDED_OFF, handed.status)
            assertTrue(handed.message, handed.message.contains("rather than repeating it"))
            assertEquals(1, pending.requests.count { it.method == "POST" })
        }

    @Test
    fun `a lost answer is settled by reading the key back`() =
        runTest {
            var held: HttpResponse? = record("accepted")
            val lost =
                ScriptedHost { request ->
                    if (request.method == "POST") throw IOException("connection reset")
                    held ?: HttpResponse(404, "record not found")
                }
            assertEquals(InvocationStatus.COMPLETED, backend(lost).execute(proposal()).status)
            held = null
            assertEquals(InvocationStatus.NOT_EXECUTED, backend(lost).execute(proposal()).status)
            val unreadable =
                ScriptedHost { request ->
                    if (request.method == "POST") throw IOException("connection reset")
                    throw IOException("still down")
                }
            val unknown = backend(unreadable).execute(proposal())
            assertEquals(InvocationStatus.UNKNOWN, unknown.status)
            assertEquals(1, unreadable.requests.count { it.method == "POST" })
            val conflict = ScriptedHost { HttpResponse(409, "idempotency conflict") }
            assertEquals(InvocationStatus.NOT_EXECUTED, backend(conflict).execute(proposal()).status)
            val refused = ScriptedHost { HttpResponse(404, "record not found") }
            assertEquals(InvocationStatus.NOT_EXECUTED, backend(refused).execute(proposal()).status)
            assertEquals(1, refused.requests.size)
        }

    @Test
    fun `setting slots and wildcard fields fill requests and results`() {
        val withSlot =
            PackageCodec.decode(
                json.replace(
                    "\"name\": \"limit\",\n            \"value\": {\n              \"argument\": \"limit\",\n              \"type\": \"integer\",\n              \"default\": 10\n            }",
                    "\"name\": \"network\",\n            \"value\": {\n              \"setting\": \"service\",\n              \"type\": \"string\"\n            }",
                ),
            )
        val conversations = withSlot.capabilities.single { it.name == "conversations" }
        val request =
            BindingArguments(conversations, mapOf("query" to "Ali"), mapOf("service" to JsonPrimitive("whatsapp")))
                .http(conversations.binding as DeclarativeBinding.Http)
        assertEquals("https://bridge.example.ts.net/v1/conversations?q=Ali&network=whatsapp", request.url)
        assertThrows(IllegalArgumentException::class.java) {
            BindingArguments(conversations, mapOf("query" to "Ali")).http(conversations.binding as DeclarativeBinding.Http)
        }
        val items =
            checkNotNull((definition.capabilities.single { it.name == "conversations" }.binding as DeclarativeBinding.Http).result.items)
        val root =
            Json.parseToJsonElement(
                """{"conversations":[{"id":"c1","name":"Book club","participants":[{"id":"a","name":"Alice","address":"+1"},
                {"id":"me","name":"","address":"+2","is_me":true}],"updated":"t","unread":true,"read_only":false,"preview":"hi"}]}""",
            )
        val projected = ItemResults.project(root, items, 6000)
        val record =
            projected.data
                .getValue("items")
                .jsonArray
                .single()
                .jsonObject
        assertEquals(listOf("Alice"), record.getValue("people").jsonArray.map { (it as JsonPrimitive).content })
        assertEquals(listOf("+1", "+2"), record.getValue("numbers").jsonArray.map { (it as JsonPrimitive).content })
        assertTrue(projected.text, projected.text.startsWith("conversationRef \"c1\" | \"Book club\" | people [\"Alice\"]"))
        assertEquals(JsonObject::class, record::class)
    }

    @Test
    fun `messaging tools are reachable only through the role and only as granted`() =
        runTest {
            val host = ScriptedHost { HttpResponse(200, """{"conversations":[]}""") }
            var settings = mapOf("service" to JsonPrimitive("whatsapp"), "label" to JsonPrimitive("WhatsApp"))
            val identity =
                com.colonelpanic.eva.capability.extensions
                    .PackageIdentity("00000000-0000-0000-0000-000000000077")
            val adapter =
                PackageAdapter(
                    { listOf(LoadedPackage(identity, definition, true, settings)) },
                    { host },
                    com.colonelpanic.eva.capability
                        .BoundedExecution(backgroundScope),
                ) { _, _, proposal -> WaitBudget(proposal.interactionMode, 30_000, null, null) }
            val persistence =
                object : com.colonelpanic.eva.capability.extensions.ExtensionGrantPersistence {
                    var value: String? = null

                    override suspend fun read() = value

                    override suspend fun write(json: String) {
                        value = json
                    }
                }
            val registry =
                com.colonelpanic.eva.capability
                    .CapabilityRegistry(emptyMap())
            val runtime =
                com.colonelpanic.eva.capability.extensions.ExtensionRuntime(
                    registry,
                    adapter,
                    com.colonelpanic.eva.capability.extensions
                        .ExtensionGrants(persistence),
                    backgroundScope,
                )
            runCurrent()
            val key =
                runtime.settings.value.entries
                    .single()
                    .key
            runtime.enable(key, true)
            runCurrent()
            val prefix = "extension.package.${identity.id}"
            assertTrue(registry.catalog.none { it.id.startsWith(prefix) })
            assertEquals(setOf("$prefix.conversations", "$prefix.contacts", "$prefix.messages"), runtime.routed.value.keys)
            runtime.mutation(key, "send", true)
            runCurrent()
            assertTrue("$prefix.send" in runtime.routed.value)
            assertTrue("$prefix.start_chat" !in runtime.routed.value)
            assertEquals("whatsapp", adapter.messagingServices().single().service)
            settings = settings + ("service" to JsonPrimitive("wa-work"))
            adapter.refresh()
            assertEquals(listOf("wa-work"), adapter.messagingServices().map { it.service })
            settings = settings + ("service" to JsonPrimitive("sms"))
            adapter.refresh()
            assertTrue(adapter.messagingServices().isEmpty())
        }
}
