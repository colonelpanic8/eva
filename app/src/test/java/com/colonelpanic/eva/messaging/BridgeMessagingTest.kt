package com.colonelpanic.eva.messaging

import com.colonelpanic.eva.adapters.declarative.BearerCredential
import com.colonelpanic.eva.adapters.declarative.PackageHttpClient
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.data.configuration.MessagingBridgeDefinition
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** A scripted bridge behind OkHttp's interceptor, the same fake the package HTTP client tests use. */
class FakeBridge(
    val origin: String = ORIGIN,
) {
    data class Recorded(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String?,
    )

    val requests = mutableListOf<Recorded>()
    var status = """{"state":"connected","transport_connected":true,"phone_responsive":true}"""
    var conversations = "[]"
    var contacts = "[]"
    var messages = "[]"
    val outbox = mutableMapOf<String, String>()
    var onQueue: (String, String, String) -> Pair<Int, String> = { path, key, body -> 202 to defaultQueue(path, key, body) }
    var onOutboxRead: (String) -> Unit = {}
    var failNextPost: IOException? = null
    var unauthorized = false

    val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val body =
                        request.body?.let { content ->
                            Buffer().also(content::writeTo).readUtf8()
                        }
                    val recorded =
                        Recorded(
                            request.method,
                            request.url.encodedPath,
                            request.url.queryParameterNames.associateWith { request.url.queryParameter(it).orEmpty() },
                            request.headers.names().associateWith { request.header(it).orEmpty() },
                            body,
                        )
                    requests += recorded
                    val (code, text) = respond(recorded)
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("scripted")
                        .body(text.toResponseBody())
                        .build()
                },
            ).build()

    private fun respond(request: Recorded): Pair<Int, String> {
        assertEquals("Bearer $TOKEN", request.headers["Authorization"])
        if (unauthorized) return 401 to "unauthorized\n"
        val path = request.path
        return when {
            request.method == "GET" && path == "/v1/status" -> {
                200 to status
            }

            request.method == "GET" && path == "/v1/conversations" -> {
                200 to """{"conversations":$conversations,"cursor":"c1"}"""
            }

            request.method == "GET" && path == "/v1/contacts" -> {
                200 to
                    """{"contacts":$contacts,"updated":"2026-09-26T10:00:00Z","stale":false}"""
            }

            request.method == "GET" && path.endsWith("/messages") -> {
                if (path.contains("/missing/")) {
                    404 to "record not found\n"
                } else {
                    200 to
                        """{"messages":$messages,"cursor":"c2","next_before":""}"""
                }
            }

            request.method == "GET" && path.startsWith("/v1/outbox/") -> {
                val key = path.removePrefix("/v1/outbox/")
                onOutboxRead(key)
                outbox[key]?.let { 200 to it } ?: (404 to "record not found\n")
            }

            request.method == "POST" -> {
                failNextPost?.let {
                    failNextPost = null
                    throw it
                }
                val key = request.headers.getValue("Idempotency-Key")
                assertTrue(Regex("[A-Za-z0-9_-]{16,128}").matches(key))
                onQueue(path, key, request.body.orEmpty())
            }

            else -> {
                404 to "record not found\n"
            }
        }
    }

    private fun defaultQueue(
        path: String,
        key: String,
        body: String,
    ): String {
        val request = Json.parseToJsonElement(body).jsonObject
        val record =
            if (path == "/v1/conversations") {
                """{"id":"$key","state":"queued","schema":1}"""
            } else {
                """{"id":"$key","state":"queued","conversation_id":${request.getValue("conversation_id")},"schema":1}"""
            }
        outbox[key] = record
        return record
    }

    companion object {
        const val ORIGIN = "https://bridge.example.ts.net"
        const val TOKEN = "bridge-secret-token"
    }
}

class BridgeMessagingTest {
    private val fake = FakeBridge()
    private var now = 1_000_000L
    private val definition = MessagingBridgeDefinition("WhatsApp", FakeBridge.ORIGIN)
    private val bridges =
        BridgeMessaging(
            bridges = { mapOf("whatsapp" to definition) },
            http = PackageHttpClient({ origin, _ -> BearerCredential.create(origin, FakeBridge.TOKEN) }, fake.client),
            clock = { now },
            pollMillis = 10,
            sendWaitMillis = 3_000,
        )
    private val whatsapp = BridgeMessaging.Bridge("whatsapp", definition)

    private val alice = """{"id":"alice@s","name":"Alice","address":"+14155550100","is_me":false}"""
    private val me = """{"id":"me@s","name":"","address":"+14155550199","is_me":true}"""

    private fun conversation(
        id: String,
        name: String,
        participants: String,
    ) = """{"schema":1,"id":"$id","name":"$name","preview":"See you there","updated":"2026-09-26T09:00:00Z","unread":true,
        "read_only":false,"protocol":"whatsapp","state":"active","participants":[$participants],
        "preview_sender_id":"alice@s","preview_direction":"incoming"}"""

    private val chat = conversation("chat-1", "", "$alice,$me")

    private fun data(outcome: String): JsonObject = Json.parseToJsonElement(outcome.substringAfter("External WhatsApp data: ")).jsonObject

    @Test
    fun `search queries conversations and contacts and returns durable attributed references`() =
        runTest {
            now =
                java.time.Instant
                    .parse("2026-09-26T11:00:00Z")
                    .toEpochMilli()
            fake.conversations = "[$chat]"
            fake.contacts =
                """[{"id":"c1","name":"Alicia","address":"+14155550111"},{"id":"c2","name":"Alice","address":"+14155550100"},{"id":"c3","name":"Al"}]"""
            val outcome = bridges.search(whatsapp, "Ali", emptyList(), 5)
            assertEquals(InvocationStatus.COMPLETED, outcome.status)
            val listing = fake.requests.single { it.path == "/v1/conversations" }
            assertEquals(mapOf("q" to "Ali", "limit" to "5"), listing.query)
            assertEquals("Ali", fake.requests.single { it.path == "/v1/contacts" }.query["q"])
            val payload = data(outcome.message)
            val row =
                payload
                    .getValue("conversations")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("bridge:whatsapp:chat-1", row.getValue("conversationRef").jsonPrimitive.content)
            assertEquals("Alice", row.getValue("name").jsonPrimitive.content)
            assertEquals(
                "Alice (+14155550100)",
                row
                    .getValue("participants")
                    .jsonArray
                    .single()
                    .jsonPrimitive.content,
            )
            assertEquals("2 hours ago", row.getValue("lastActivity").jsonPrimitive.content)
            assertEquals("true", row.getValue("unread").jsonPrimitive.content)
            assertEquals("Alice: See you there", row.getValue("preview").jsonPrimitive.content)
            assertNull(row["group"])
            // Alice already has a chat; a contact without a number cannot be addressed at all.
            val contact =
                payload
                    .getValue("contacts")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("Alicia", contact.getValue("name").jsonPrimitive.content)
            assertEquals("+14155550111", contact.getValue("number").jsonPrimitive.content)
            assertFalse(outcome.message.contains(FakeBridge.TOKEN))
            assertTrue(outcome.message.contains("service whatsapp"))
        }

    @Test
    fun `search by participants keeps only conversations with every number and skips contacts`() =
        runTest {
            val bob = """{"id":"bob@s","name":"Bob","address":"+14155550122","is_me":false}"""
            val group = conversation("group-1", "Book club", "$alice,$me,$bob")
            fake.conversations = "[$group,$chat]"
            val outcome = bridges.search(whatsapp, null, listOf("+1 (415) 555-0100"), 5)
            val rows = data(outcome.message).getValue("conversations").jsonArray.map { it.jsonObject }
            assertEquals(
                listOf("bridge:whatsapp:chat-1", "bridge:whatsapp:group-1"),
                rows.map { it.getValue("conversationRef").jsonPrimitive.content },
            )
            assertEquals("true", rows[1].getValue("group").jsonPrimitive.content)
            assertEquals("14155550100", fake.requests.single { it.path == "/v1/conversations" }.query["q"])
            assertTrue(fake.requests.none { it.path == "/v1/contacts" })
            assertTrue(
                data(bridges.search(whatsapp, null, listOf("+14155550122"), 5).message).getValue("conversations").jsonArray.size == 1,
            )
        }

    @Test
    fun `history names senders reports sent status and describes attachments oldest first`() =
        runTest {
            now =
                java.time.Instant
                    .parse("2026-09-26T10:00:00Z")
                    .toEpochMilli()
            fake.conversations = "[$chat]"
            fake.messages =
                """[
                {"id":"m2","conversation_id":"chat-1","sender_id":"me@s","time":"2026-09-26T09:30:00Z","text":"On my way","direction":"outgoing",
                 "status":"delivered","deleted":false,"attachments":[],"reactions":[{"emoji":"👍","participants":["alice@s"]}]},
                {"id":"m1","conversation_id":"chat-1","sender_id":"alice@s","time":"2026-09-26T09:00:00Z","text":"","direction":"incoming",
                 "status":"read","deleted":false,"attachments":[{"id":"a1","name":"park.jpg","mime":"image/jpeg","size":1234,"available":true}],"reactions":[]}
                ]"""
            val outcome = bridges.read(whatsapp, "chat-1", 25)
            assertEquals(InvocationStatus.COMPLETED, outcome.status)
            assertEquals("/v1/conversations/chat-1/messages", fake.requests.first().path)
            assertEquals("25", fake.requests.first().query["limit"])
            val payload = data(outcome.message)
            assertEquals("Alice", payload.getValue("conversation").jsonPrimitive.content)
            val messages = payload.getValue("messages").jsonArray.map { it.jsonObject }
            assertEquals(listOf("Alice", "You"), messages.map { it.getValue("from").jsonPrimitive.content })
            assertEquals("1 hour ago", messages[0].getValue("when").jsonPrimitive.content)
            assertEquals(
                "Photo park.jpg image/jpeg",
                messages[0]
                    .getValue("attachments")
                    .jsonArray
                    .single()
                    .jsonPrimitive.content,
            )
            assertNull(messages[0]["status"])
            assertEquals("delivered", messages[1].getValue("status").jsonPrimitive.content)
            assertEquals(
                "👍 ×1",
                messages[1]
                    .getValue("reactions")
                    .jsonArray
                    .single()
                    .jsonPrimitive.content,
            )
            assertEquals(InvocationStatus.FAILED, bridges.read(whatsapp, "missing", 5).status)
        }

    @Test
    fun `send maps outbox states to distinct outcomes`() =
        runTest {
            fake.conversations = "[$chat]"

            suspend fun outcomeFor(state: String): Pair<InvocationStatus, String> {
                fake.requests.clear()
                fake.onQueue = { _, key, _ ->
                    val record = """{"id":"$key","state":"$state","conversation_id":"chat-1","detail":"server said no","schema":1}"""
                    fake.outbox[key] = record
                    202 to record
                }
                val outcome = bridges.send(whatsapp, BridgeMessaging.Target.Conversation("chat-1"), "Hi", "k".repeat(64))
                assertEquals(1, fake.requests.count { it.method == "POST" })
                return outcome.status to outcome.message
            }
            outcomeFor("accepted").let { (status, message) ->
                assertEquals(InvocationStatus.COMPLETED, status)
                assertTrue(message, message.contains("Sent to Alice on WhatsApp") && message.contains("not a delivery or read receipt"))
            }
            assertEquals(InvocationStatus.COMPLETED, outcomeFor("confirmed").first)
            outcomeFor("rejected").let { (status, message) ->
                assertEquals(InvocationStatus.NOT_EXECUTED, status)
                assertTrue(message, message.contains("server said no") && message.contains("Nothing was sent"))
            }
            outcomeFor("ambiguous").let { (status, message) ->
                assertEquals(InvocationStatus.UNKNOWN, status)
                assertTrue(message, message.contains("does not resend"))
            }
            assertEquals(InvocationStatus.NOT_EXECUTED, outcomeFor("canceled").first)
            val body = Json.parseToJsonElement(fake.requests.single { it.method == "POST" }.body!!).jsonObject
            assertEquals(JsonPrimitive("chat-1"), body["conversation_id"])
            assertEquals(JsonPrimitive("Hi"), body["text"])
        }

    @Test
    fun `a send still queued at the deadline is handed off with the bridge state`() =
        runTest {
            fake.conversations = "[$chat]"
            fake.onOutboxRead = { now += 1_000 }
            fake.status =
                """{"state":"connecting","reason":"transient_connection_failure","detail":"dialing","transport_connected":false,"phone_responsive":false}"""
            val outcome = bridges.send(whatsapp, BridgeMessaging.Target.Conversation("chat-1"), "Hi", "k".repeat(64))
            assertEquals(InvocationStatus.HANDED_OFF, outcome.status)
            assertTrue(outcome.message, outcome.message.contains("queued at the WhatsApp bridge"))
            assertTrue(outcome.message, outcome.message.contains("connecting (transient_connection_failure): dialing"))
            assertTrue(fake.requests.count { it.path == "/v1/outbox/" + "k".repeat(64) } >= 3)
            assertEquals(1, fake.requests.count { it.method == "POST" })
        }

    @Test
    fun `a bridge that needs re-pairing or rejects the token refuses before queuing`() =
        runTest {
            fake.status =
                """{"state":"authentication_required","reason":"session_expired","detail":"re-pair","transport_connected":false,"phone_responsive":false}"""
            val refused = bridges.send(whatsapp, BridgeMessaging.Target.Conversation("chat-1"), "Hi", "k".repeat(64))
            assertEquals(InvocationStatus.NOT_EXECUTED, refused.status)
            assertTrue(refused.message, refused.message.contains("Re-pair") && refused.message.contains("Nothing was sent"))
            assertTrue(fake.requests.none { it.method == "POST" })
            fake.unauthorized = true
            val token = bridges.send(whatsapp, BridgeMessaging.Target.Conversation("chat-1"), "Hi", "k".repeat(64))
            assertEquals(InvocationStatus.NOT_EXECUTED, token.status)
            assertTrue(token.message, token.message.contains("Settings → Messaging"))
            assertEquals(InvocationStatus.NOT_EXECUTED, bridges.search(whatsapp, "x", emptyList(), 5).status)
            assertTrue(bridges.check("whatsapp").contains("token"))
        }

    @Test
    fun `an unreachable bridge fails reads and never falls through`() =
        runTest {
            val unreachable =
                BridgeMessaging(
                    { mapOf("whatsapp" to definition) },
                    PackageHttpClient(
                        { origin, _ -> BearerCredential.create(origin, FakeBridge.TOKEN) },
                        OkHttpClient.Builder().addInterceptor { throw IOException("down") }.build(),
                    ),
                )
            assertEquals(InvocationStatus.FAILED, unreachable.search(whatsapp, "x", emptyList(), 5).status)
            assertEquals(
                InvocationStatus.FAILED,
                unreachable.send(whatsapp, BridgeMessaging.Target.Conversation("chat-1"), "Hi", "k".repeat(64)).status,
            )
            assertTrue(unreachable.check("whatsapp").contains("Could not reach"))
            val missingToken = BridgeMessaging({ mapOf("whatsapp" to definition) }, PackageHttpClient({ _, _ -> null }, fake.client))
            val outcome = missingToken.send(whatsapp, BridgeMessaging.Target.Conversation("chat-1"), "Hi", "k".repeat(64))
            assertEquals(InvocationStatus.NOT_EXECUTED, outcome.status)
            assertTrue(outcome.message, outcome.message.contains("No usable token"))
        }

    @Test
    fun `a new chat is created under a derived key before the message is queued`() =
        runTest {
            fake.conversations = "[$chat]"
            var reads = 0
            fake.onOutboxRead = { key ->
                if (key.endsWith("-chat") && ++reads == 2) {
                    fake.outbox[key] = """{"id":"$key","state":"accepted","conversation_id":"bob@s","schema":1}"""
                }
            }
            fake.onQueue = { path, key, body ->
                if (path == "/v1/conversations") {
                    202 to """{"id":"$key","state":"queued","schema":1}""".also { fake.outbox[key] = it }
                } else {
                    202 to """{"id":"$key","state":"accepted","conversation_id":"bob@s","schema":1}""".also { fake.outbox[key] = it }
                }
            }
            val key = "s".repeat(64)
            val outcome = bridges.send(whatsapp, BridgeMessaging.Target.Recipients(listOf("+14155550122")), "Hello Bob", key)
            assertEquals(InvocationStatus.COMPLETED, outcome.status)
            assertTrue(outcome.message, outcome.message.contains("+14155550122"))
            val posts = fake.requests.filter { it.method == "POST" }
            assertEquals(listOf("/v1/conversations", "/v1/messages"), posts.map { it.path })
            assertEquals("$key-chat", posts[0].headers["Idempotency-Key"])
            assertEquals("""{"recipients":["+14155550122"]}""", posts[0].body)
            assertEquals(key, posts[1].headers["Idempotency-Key"])
            assertEquals(JsonPrimitive("bob@s"), Json.parseToJsonElement(posts[1].body!!).jsonObject["conversation_id"])
            // The number is searched first so an existing chat is reused rather than created again.
            assertEquals("14155550122", fake.requests.first { it.path == "/v1/conversations" && it.method == "GET" }.query["q"])
        }

    @Test
    fun `an existing chat with exactly those people is reused and a refused chat sends nothing`() =
        runTest {
            fake.conversations = "[$chat]"
            fake.onQueue = { _, key, _ ->
                202 to """{"id":"$key","state":"accepted","conversation_id":"chat-1","schema":1}""".also { fake.outbox[key] = it }
            }
            val reused = bridges.send(whatsapp, BridgeMessaging.Target.Recipients(listOf("+14155550100")), "Hi", "r".repeat(64))
            assertEquals(InvocationStatus.COMPLETED, reused.status)
            assertEquals(listOf("/v1/messages"), fake.requests.filter { it.method == "POST" }.map { it.path })
            fake.requests.clear()
            fake.onQueue = { _, key, _ ->
                202 to
                    """{"id":"$key","state":"rejected","detail":"+14155550133 is not on WhatsApp","schema":1}""".also {
                        fake.outbox[key] =
                            it
                    }
            }
            val refused = bridges.send(whatsapp, BridgeMessaging.Target.Recipients(listOf("+14155550133")), "Hi", "r".repeat(64))
            assertEquals(InvocationStatus.NOT_EXECUTED, refused.status)
            assertTrue(refused.message, refused.message.contains("is not on WhatsApp") && refused.message.contains("Nothing was sent"))
            assertEquals(listOf("/v1/conversations"), fake.requests.filter { it.method == "POST" }.map { it.path })
        }

    @Test
    fun `a lost answer is resolved by reading the key back never by sending again`() =
        runTest {
            fake.conversations = "[$chat]"
            val key = "l".repeat(64)
            fake.failNextPost = IOException("connection reset")
            fake.outbox[key] = """{"id":"$key","state":"accepted","conversation_id":"chat-1","schema":1}"""
            val kept = bridges.send(whatsapp, BridgeMessaging.Target.Conversation("chat-1"), "Hi", key)
            assertEquals(InvocationStatus.COMPLETED, kept.status)
            assertEquals(1, fake.requests.count { it.method == "POST" })
            fake.requests.clear()
            fake.outbox.clear()
            fake.failNextPost = IOException("connection reset")
            val dropped = bridges.send(whatsapp, BridgeMessaging.Target.Conversation("chat-1"), "Hi", key)
            assertEquals(InvocationStatus.NOT_EXECUTED, dropped.status)
            assertEquals(1, fake.requests.count { it.method == "POST" })
        }

    @Test
    fun `idempotency keys are stable per invocation and valid for the bridge`() {
        val key = bridges.idempotencyKey("call-1", "fp")
        assertEquals(key, bridges.idempotencyKey("call-1", "fp"))
        assertTrue(Regex("[a-f0-9]{64}").matches(key))
        assertTrue(key != bridges.idempotencyKey("call-2", "fp") && key != bridges.idempotencyKey("call-1", "other"))
        assertEquals("whatsapp" to "chat-1", bridges.parseReference("bridge:whatsapp:chat-1"))
        assertNull(bridges.parseReference("notification:abc"))
        assertEquals("whatsapp", bridges.resolve("WhatsApp")?.name)
        assertNull(bridges.resolve("whats"))
        assertNull(bridges.resolve("sms"))
    }
}
