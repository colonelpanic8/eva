package com.colonelpanic.eva.capability.extensions

import com.colonelpanic.eva.capability.BoundedJson
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionProtocolTest {
    private val schema =
        Json
            .parseToJsonElement(
                """{"type":"object","properties":{
        "query":{"type":"string","maxLength":100},
        "limit":{"type":"integer","minimum":1,"maximum":50},
        "overdue":{"type":"boolean"}},"required":["query"],"additionalProperties":false}""",
            ).jsonObject
    private val capability =
        """
        {
        "name":"search","title":"Search agenda","description":"Search a bounded set.",
        "inputSchema":$schema,"effects":"read",
        "execution":{"requiresForeground":false,"maxDurationMillis":30000,"cancellation":"none","idempotency":"none"},
        "result":{"mediaType":"text/plain","maxBytes":16384}}
        """.trimIndent()

    private fun describe(
        capabilities: String = capability,
        revision: String = "v1",
    ): String =
        """
        {
        "protocolVersion":1,"status":"completed","reasonCode":null,"message":"","truncated":false,
        "descriptor":{"protocolVersion":1,"descriptorRevision":"$revision","authorizationScopeRevision":"account1",
        "title":"Agenda","schemaVersion":"flat-scalar-v1","capabilities":[$capabilities]}}
        """.trimIndent()

    @Test
    fun `descriptor yields validated contract digest independent of object order`() {
        val first = ExtensionProtocol.describe(describe()).descriptor!!
        val reversed =
            JsonObject(
                Json
                    .parseToJsonElement(describe())
                    .jsonObject.entries
                    .reversed()
                    .associate { it.toPair() },
            )
        assertEquals(first, ExtensionProtocol.describe(reversed.toString()).descriptor)
        assertEquals(Effect.READ, first.capabilities.single().effect)
        assertNotEquals(first.digest, ExtensionProtocol.describe(describe(revision = "v2")).descriptor!!.digest)
        assertNotEquals(first.digest, ExtensionProtocol.describe(describe().replace("account1", "account2")).descriptor!!.digest)
    }

    @Test
    fun `unknown effects stay unknown and unsupported metadata never becomes a grant`() {
        assertEquals(
            Effect.UNKNOWN,
            ExtensionProtocol
                .describe(describe(capability.replace("\"read\"", "\"unknown\"")))
                .descriptor!!
                .capabilities
                .single()
                .effect,
        )
        listOf(
            describe().replace("\"protocolVersion\":1", "\"protocolVersion\":2"),
            describe().replace("flat-scalar-v1", "anything"),
            describe().replace("\"effects\":\"read\",", ""),
            describe().replace("\"read\"", "\"harmless\""),
            describe().replace("\"requiresForeground\":false", "\"requiresForeground\":true"),
            describe().replace("30000", "60001"),
            describe().replace("16384", "16385"),
            describe().replace("\"idempotency\":\"none\"", "\"idempotency\":\"guaranteed\""),
            describe().replace("\"title\":\"Agenda\"", "\"title\":\"Agenda\",\"id\":\"eva\""),
        ).forEach { payload -> assertThrows(Exception::class.java) { ExtensionProtocol.describe(payload) } }
    }

    @Test
    fun `duplicate and namespaced capabilities are rejected atomically`() {
        listOf(describe("$capability,$capability"), describe(capability.replace("\"search\"", "\"eva.android.sms\"")), describe(""))
            .forEach { assertThrows(Exception::class.java) { ExtensionProtocol.describe(it) } }
    }

    @Test
    fun `wire parsing rejects duplicate escaped keys invalid unicode and trailing content`() {
        listOf(
            """{"a":1,"\u0061":2}""",
            """{"a":"\uD800"}""",
            """{"a":NaN}""",
            """{"a":1e9999}""",
            """{"a":1,}""",
            """{"a":1} []""",
            "[[[[[[[[[[[[[[[[[[0]]]]]]]]]]]]]]]]]]",
        ).forEach { assertThrows(Exception::class.java) { BoundedJson.parse(it, 1000) } }
        assertEquals(JsonPrimitive("😀"), BoundedJson.parse("\"\\uD83D\\uDE00\"", 1000))
    }

    @Test
    fun `byte limits include multibyte text and envelope before parsing`() {
        val json = "\"éé\""
        assertThrows(Exception::class.java) { BoundedJson.parse(json, 5) }
        assertEquals(JsonPrimitive("éé"), BoundedJson.parse(json, 6))
        assertThrows(Exception::class.java) { ExtensionProtocol.describe(" ".repeat(ExtensionProtocol.CATALOG_BYTES) + describe()) }
        assertThrows(Exception::class.java) { ExtensionProtocol.arguments(schema, " ".repeat(ExtensionProtocol.ARGUMENT_BYTES) + "{}") }
        val reply = ResultReply(ExecutionOutcome(InvocationStatus.COMPLETED, "done"), null, false)
        assertThrows(Exception::class.java) { ExtensionProtocol.encodeResult(reply, 10) }
        assertThrows(
            Exception::class.java,
        ) { ExtensionProtocol.encodeResult(reply.copy(outcome = reply.outcome.copy(message = "a".repeat(4001)))) }
    }

    @Test
    fun `encoding restores scalar types and rejects unrecognized arguments`() {
        val payload = ExtensionProtocol.encodeArguments(schema, mapOf("query" to "taxes", "limit" to "3", "overdue" to "true"))
        val parsed = ExtensionProtocol.arguments(schema, payload)
        assertFalse((parsed.getValue("limit") as JsonPrimitive).isString)
        assertEquals(JsonPrimitive(true), parsed["overdue"])
        listOf(
            """{"query":"tax","limit":"3"}""",
            """{"query":null}""",
            """{"query":"tax","extra":true}""",
            """{"query":"tax","limit":51}""",
        ).forEach { assertThrows(Exception::class.java) { ExtensionProtocol.arguments(schema, it) } }
    }

    @Test
    fun `flat schema rejects nesting unsafe integers duplicate enums and defaults`() {
        val prefix = """{"type":"object","properties":{"value":"""
        val suffix = """},"required":[],"additionalProperties":false}"""
        listOf(
            """{"type":"object","properties":{},"required":[],"additionalProperties":false}""",
            """{"type":"array"}""",
            """{"type":"string","default":"a"}""",
            """{"type":"integer","maximum":9007199254740992}""",
            """{"type":"number","enum":[1,1.0]}""",
        ).forEach {
            assertThrows(Exception::class.java) { ExtensionProtocol.checkSchema(Json.parseToJsonElement(prefix + it + suffix).jsonObject) }
        }
        val integers = Json.parseToJsonElement(prefix + """{"type":"integer"}""" + suffix).jsonObject
        listOf("9007199254740992", "9007199254740991.1").forEach {
            assertThrows(Exception::class.java) { ExtensionProtocol.arguments(integers, """{"value":$it}""") }
        }
    }

    @Test
    fun `terminal replies round trip without upgrading handoff or uncertainty`() {
        listOf(
            InvocationStatus.COMPLETED,
            InvocationStatus.NOT_EXECUTED,
            InvocationStatus.FAILED,
            InvocationStatus.HANDED_OFF,
            InvocationStatus.UNKNOWN,
        ).forEach {
            val reply = ResultReply(ExecutionOutcome(it, "provider reports"), null, true)
            assertEquals(reply, ExtensionProtocol.executeResult(ExtensionProtocol.encodeResult(reply)))
        }
        val uncertain = ResultReply(ExecutionOutcome(InvocationStatus.UNKNOWN, "May have executed"), "deadline_exceeded", false)
        assertEquals(uncertain, ExtensionProtocol.executeResult(ExtensionProtocol.encodeResult(uncertain)))
        listOf("stale_descriptor", "not_configured", "busy", "invalid_arguments", "unauthorized_caller").forEach {
            assertThrows(Exception::class.java) { ExtensionProtocol.encodeResult(uncertain.copy(reasonCode = it)) }
        }
        assertThrows(Exception::class.java) { ExtensionProtocol.encodeResult(uncertain.copy(reasonCode = "retry_me")) }
        assertThrows(Exception::class.java) {
            ExtensionProtocol.executeResult(
                """{"protocolVersion":1,"status":"accepted","reasonCode":null,"message":"","truncated":false}""",
            )
        }
    }

    @Test
    fun `failed discovery never returns a partial descriptor`() {
        val reply =
            ExtensionProtocol.describe(
                """{"protocolVersion":1,"status":"not_executed","reasonCode":"busy","message":"Busy","truncated":false,"descriptor":null}""",
            )
        assertNull(reply.descriptor)
        assertEquals("busy", reply.result.reasonCode)
        assertThrows(Exception::class.java) { ExtensionProtocol.describe(describe().replace("\"truncated\":false", "\"truncated\":true")) }
        assertTrue(reply.result.outcome.status == InvocationStatus.NOT_EXECUTED)
    }
}
