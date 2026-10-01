package com.colonelpanic.eva.diagnostics

import com.colonelpanic.eva.capability.ActionInitiator
import com.colonelpanic.eva.capability.CapabilitySource
import com.colonelpanic.eva.capability.InitiatorKind
import com.colonelpanic.eva.capability.InvocationRecord
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.capability.ReceiptProvenance
import com.colonelpanic.eva.conversation.MemoryConversationStore
import com.colonelpanic.eva.conversation.NoticeKind
import com.colonelpanic.eva.conversation.OfferedTool
import com.colonelpanic.eva.conversation.SessionCatalogRecord
import com.colonelpanic.eva.conversation.SessionKind
import com.colonelpanic.eva.conversation.ThreadItem
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsExportTest {
    private val store = MemoryConversationStore()
    private val repository = MemoryInvocationRepository()
    private val environment = mapOf("appVersion" to "1.2.3", "versionCode" to "1002003", "buildType" to "release")

    private suspend fun export(
        threadId: String,
        maxItems: Int = DiagnosticsExport.MAX_ITEMS,
        secrets: List<String> = emptyList(),
        trace: List<TraceEvent> = emptyList(),
    ): JsonObject =
        DiagnosticsExport.assemble(
            DiagnosticsExport.collect(store, repository, threadId, environment, emptyList(), trace, 1_000, maxItems),
            Redactor(secrets),
        )

    @Test
    fun `items keep their order and actions join their receipts with initiator and leg`() =
        runTest {
            val thread = store.createThread("Lights")
            val turn = store.openTurn(thread.id, "Turn on the lights", "turn-1")
            val initiator = ActionInitiator(InitiatorKind.TEXT_AGENT, inputId = "input", legId = "leg")
            store.append(ThreadItem.UserMessage("u", thread.id, turn.id, 1, "Turn on the lights", spoken = true))
            store.append(ThreadItem.TextLeg("leg", thread.id, turn.id, 2, "Turn on the lights", "Full instructions", 4))
            store.append(
                ThreadItem.ActionCall(
                    "a",
                    thread.id,
                    turn.id,
                    3,
                    "call-1",
                    "extension.home.lights",
                    "Lights",
                    mapOf("room" to "den"),
                    "leg",
                    initiator,
                ),
            )
            store.append(ThreadItem.AssistantMessage("r", thread.id, turn.id, 4, "Done", spoken = false))
            store.append(ThreadItem.Notice("n", thread.id, null, 5, NoticeKind.SESSION_ENDED, "Call ended: by you"))
            repository.claim(
                InvocationRecord(
                    "call-1",
                    "fingerprint",
                    "Turn on the lights",
                    null,
                    InvocationStatus.COMPLETED,
                    "Lights on",
                    3,
                    "extension.home.lights",
                    "revision",
                    "Lights",
                    mapOf("room" to "den"),
                    ReceiptProvenance(CapabilitySource("home", "Home"), "binding"),
                    thread.id,
                    turn.id,
                    buildJsonObject { put("brightness", 80) },
                    initiator,
                ),
            )
            store.recordSessionCatalog(
                SessionCatalogRecord(
                    "s",
                    thread.id,
                    null,
                    0,
                    SessionKind.VOICE,
                    null,
                    "gpt-realtime",
                    "revision",
                    listOf(OfferedTool("extension.home.lights", "Lights")),
                    listOf("extension.big.action"),
                ),
            )

            val document = export(thread.id)
            val items = document.getValue("items").jsonArray.map { it.jsonObject }
            assertEquals(
                listOf("user", "text_leg", "action", "assistant", "notice"),
                items.map { it.getValue("type").jsonPrimitive.content },
            )
            assertEquals("true", items[0].getValue("spoken").jsonPrimitive.content)
            assertEquals("turn-1", items[0].getValue("turnId").jsonPrimitive.content)
            assertEquals("Full instructions", items[1].getValue("instructions").jsonPrimitive.content)
            assertEquals(4, items[1].getValue("historyItems").jsonPrimitive.int)
            val action = items[2]
            assertEquals("leg", action.getValue("legId").jsonPrimitive.content)
            assertEquals(
                "text_agent",
                action
                    .getValue("initiator")
                    .jsonObject
                    .getValue("kind")
                    .jsonPrimitive.content,
            )
            val receipt = action.getValue("receipt").jsonObject
            assertEquals("COMPLETED", receipt.getValue("status").jsonPrimitive.content)
            assertEquals("Lights on", receipt.getValue("message").jsonPrimitive.content)
            assertEquals(
                80,
                receipt
                    .getValue("data")
                    .jsonObject
                    .getValue("brightness")
                    .jsonPrimitive.int,
            )
            assertEquals(
                "home",
                receipt
                    .getValue("provenance")
                    .jsonObject
                    .getValue("source")
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "OPEN",
                document
                    .getValue("turns")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("status")
                    .jsonPrimitive.content,
            )
            val session =
                document
                    .getValue("sessions")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("gpt-realtime", session.getValue("model").jsonPrimitive.content)
            assertEquals(listOf("extension.big.action"), session.getValue("excludedTools").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(
                0,
                document
                    .getValue("bounds")
                    .jsonObject
                    .getValue("items")
                    .jsonObject
                    .getValue("omitted")
                    .jsonPrimitive.int,
            )
        }

    @Test
    fun `a bounded export keeps the newest items and states how many it omitted`() =
        runTest {
            val thread = store.createThread("Long")
            repeat(5) { store.append(ThreadItem.UserMessage("m$it", thread.id, null, it.toLong(), "message $it", spoken = false)) }

            val document = export(thread.id, maxItems = 2)
            val items = document.getValue("items").jsonArray.map { it.jsonObject }
            assertEquals(listOf("message 3", "message 4"), items.map { it.getValue("text").jsonPrimitive.content })
            assertEquals(listOf(3, 4), items.map { it.getValue("index").jsonPrimitive.int })
            val bounds =
                document
                    .getValue("bounds")
                    .jsonObject
                    .getValue("items")
                    .jsonObject
            assertEquals(5, bounds.getValue("total").jsonPrimitive.int)
            assertEquals(3, bounds.getValue("omitted").jsonPrimitive.int)
            assertEquals("3 earlier items omitted", bounds.getValue("statement").jsonPrimitive.content)
            assertTrue(DiagnosticsExport.summaryText(document).contains("(3 earlier items omitted)"))
        }

    @Test
    fun `an action without a journal record says so instead of disappearing`() =
        runTest {
            val thread = store.createThread("Lost")
            store.append(ThreadItem.ActionCall("a", thread.id, null, 1, "lost", "eva.test", "Test", emptyMap()))
            val action =
                export(thread.id)
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
            assertTrue(
                action
                    .getValue("receipt")
                    .jsonPrimitive.content
                    .startsWith("missing"),
            )
        }

    @Test
    fun `credentials never appear while message content is kept`() =
        runTest {
            val stored = "portal-token-0123456789"
            val thread = store.createThread("Secrets")
            store.append(ThreadItem.UserMessage("u", thread.id, null, 1, "My grocery list is eggs; key is $stored", spoken = false))
            store.append(
                ThreadItem.ActionCall(
                    "a",
                    thread.id,
                    null,
                    2,
                    "call",
                    "extension.http.post",
                    "Post",
                    mapOf(
                        "apiKey" to "plain-value-1",
                        "headers" to "Authorization: Bearer abcdefghijklmnop",
                        "url" to "https://example.com/hook?token=qwertyuiop&page=2",
                        "body" to "hello",
                    ),
                ),
            )
            repository.claim(
                InvocationRecord(
                    "call",
                    "f",
                    "request",
                    null,
                    InvocationStatus.FAILED,
                    "Rejected sk-proj-ABCDEFGHIJKLMNOPQRSTUV for https://user:hunter22@example.com",
                    2,
                    "extension.http.post",
                    "r",
                    data =
                        buildJsonObject {
                            put(
                                "access_token",
                                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U",
                            )
                            put("credential", "service/home")
                            put("inputTokens", 42)
                            put("note", "ghp_abcdefghijklmnopqrstuvwxyz0123456789")
                        },
                ),
            )
            val trace = listOf(TraceEvent(1, TraceLevel.INFO, "provider.failure", mapOf("message" to "401 for $stored")))

            val rendered = DiagnosticsExport.render(export(thread.id, secrets = listOf(stored), trace = trace))
            listOf(
                stored,
                "plain-value-1",
                "abcdefghijklmnop",
                "qwertyuiop",
                "sk-proj-ABCDEFGHIJKLMNOPQRSTUV",
                "hunter22",
                "eyJhbGciOiJIUzI1NiJ9",
                "service/home",
                "ghp_abcdefghijklmnopqrstuvwxyz0123456789",
            ).forEach { assertFalse("$it leaked", rendered.contains(it)) }
            listOf("My grocery list is eggs", "hello", "page=2", "\"inputTokens\": 42", Redactor.REDACTED).forEach {
                assertTrue("$it missing", rendered.contains(it))
            }
        }

    @Test
    fun `realtime wire events reach the thread export redacted and the logs export without text`() =
        runTest {
            val thread = store.createThread("Wire")
            val wire =
                listOf(
                    ProviderWireEvent(
                        1,
                        1,
                        "c",
                        false,
                        "conversation.item.input_audio_transcription.completed",
                        mapOf("item_id" to "i1"),
                        null,
                        "my key is sk-abcdefghijklmnopqrstuv",
                    ),
                    ProviderWireEvent(2, 2, "c", false, "response.done", mapOf("response.id" to "r1"), "completed", null),
                )
            val document =
                DiagnosticsExport.assemble(
                    DiagnosticsExport.collect(
                        store,
                        repository,
                        thread.id,
                        environment,
                        emptyList(),
                        emptyList(),
                        3,
                        providerEvents = wire,
                    ),
                    Redactor(),
                )
            val events = document.getValue("providerEvents").jsonArray.map { it.jsonObject }
            assertEquals(
                listOf("conversation.item.input_audio_transcription.completed", "response.done"),
                events.map {
                    it.getValue("type").jsonPrimitive.content
                },
            )
            assertEquals("my key is ${Redactor.REDACTED}", events[0].getValue("text").jsonPrimitive.content)
            assertEquals("completed", events[1].getValue("status").jsonPrimitive.content)

            val logs = DiagnosticsExport.logs(environment, emptyList(), 3, Redactor(), wire)
            assertFalse(DiagnosticsExport.render(logs).contains("my key is"))
            assertEquals(2, logs.getValue("providerEvents").jsonArray.size)
        }

    @Test
    fun `long text is cut with an explicit count of what was omitted`() =
        runTest {
            val thread = store.createThread("Long text")
            store.append(
                ThreadItem.AssistantMessage("r", thread.id, null, 1, "x".repeat(DiagnosticsExport.MAX_TEXT_CHARS + 10), spoken = false),
            )
            val text =
                export(thread.id)
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive.content
            assertTrue(text.endsWith("…[10 more characters omitted]"))
        }

    @Test
    fun `sensitive keys are recognised across naming styles without catching ordinary ones`() {
        listOf("apiKey", "api_key", "X-Api-Key", "Authorization", "client_secret", "portalToken", "password", "credential", "set-cookie")
            .forEach { assertTrue(it, Redactor.sensitiveKey(it)) }
        listOf("taskId", "inputTokens", "author", "pinned", "keyword", "title", "goal")
            .forEach { assertFalse(it, Redactor.sensitiveKey(it)) }
        assertEquals(JsonPrimitive(Redactor.REDACTED), Redactor().json(buildJsonObject { put("token", "abc") }).jsonObject["token"])
    }
}
