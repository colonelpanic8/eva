package com.colonelpanic.eva.capability.extensions

import com.colonelpanic.eva.capability.BoundedJson
import com.colonelpanic.eva.capability.CallEnding
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
        "overdue":{"type":"boolean"},
        "tags":{"type":"array","items":{"type":"string","maxLength":20},"maxItems":4}},
        "required":["query"],"additionalProperties":false}""",
            ).jsonObject
    private val outputSchema =
        """{"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{
        "id":{"type":"string"},"title":{"type":"string"}},"required":["id"],"additionalProperties":true}}},
        "required":["items"],"additionalProperties":false}"""
    private val capability =
        """
        {
        "tool":{"name":"search","title":"Search agenda","description":"Search a bounded set.",
        "inputSchema":$schema,"outputSchema":$outputSchema,
        "annotations":{"readOnlyHint":true,"openWorldHint":true}},
        "effects":"read",
        "execution":{"mode":"synchronous","requiresForeground":false,"maxWaitMillis":30000},
        "result":{"maxBytes":16384}}
        """.trimIndent()

    private fun describe(
        capabilities: String = capability,
        revision: String = "v1",
    ): String =
        """
        {
        "protocolVersion":1,"status":"completed","reasonCode":null,"truncated":false,"content":[],
        "descriptor":{"protocolVersion":1,"descriptorRevision":"$revision","authorizationScopeRevision":"account1",
        "title":"Agenda","capabilities":[$capabilities]}}
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
        val parsed = first.capabilities.single()
        assertEquals(Effect.READ, parsed.effect)
        assertEquals("Search agenda", parsed.title)
        assertEquals(30_000L, parsed.maxWaitMillis)
        assertEquals(Json.parseToJsonElement(outputSchema), parsed.outputSchema)
        assertEquals(JsonPrimitive(true), parsed.annotations!!["openWorldHint"])
        assertNotEquals(first.digest, ExtensionProtocol.describe(describe(revision = "v2")).descriptor!!.digest)
        assertNotEquals(first.digest, ExtensionProtocol.describe(describe().replace("account1", "account2")).descriptor!!.digest)
    }

    @Test
    fun `meta objects are digested but not interpreted while other unknown fields fail`() {
        val withMeta = describe().replace("\"title\":\"Agenda\"", "\"title\":\"Agenda\",\"_meta\":{\"vendor\":{\"build\":7}}")
        val plain = ExtensionProtocol.describe(describe()).descriptor!!
        val annotated = ExtensionProtocol.describe(withMeta).descriptor!!
        assertEquals(plain.capabilities, annotated.capabilities)
        assertNotEquals(plain.digest, annotated.digest)
        assertThrows(
            Exception::class.java,
        ) { ExtensionProtocol.describe(withMeta.replace("\"_meta\":{\"vendor\":{\"build\":7}}", "\"_meta\":7")) }
        assertThrows(Exception::class.java) {
            ExtensionProtocol.describe(describe().replace("\"title\":\"Agenda\"", "\"title\":\"Agenda\",\"id\":\"eva\""))
        }
        val request = Json.parseToJsonElement(ExtensionProtocol.describeRequest()).jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive(1))), request["supportedProtocolVersions"])
        assertEquals(
            JsonArray(listOf("secrets", "credentials", "credential-check").map(::JsonPrimitive)),
            request["features"],
        )
    }

    @Test
    fun `declared credentials are bounded unique password entries that belong to the contract digest`() {
        fun credential(
            name: String = "passphrase",
            title: String = "Key passphrase",
            description: String = "Unlocks the PGP key.",
            kind: String = "password",
            extra: String = "",
        ) = """{"name":"$name","title":"$title","description":"$description","kind":"$kind"$extra}"""

        fun declaring(vararg credentials: String) =
            describe().replace("\"title\":\"Agenda\",", "\"title\":\"Agenda\",\"credentials\":[${credentials.joinToString(",")}],")

        val plain = ExtensionProtocol.describe(describe()).descriptor!!
        val declared = ExtensionProtocol.describe(declaring(credential(), credential("pin", "PIN", "x".repeat(500)))).descriptor!!
        assertEquals(
            listOf(CredentialSpec("passphrase", "Key passphrase", "Unlocks the PGP key."), CredentialSpec("pin", "PIN", "x".repeat(500))),
            declared.credentials,
        )
        assertTrue(plain.credentials.isEmpty())
        assertNotEquals(plain.digest, declared.digest)
        assertEquals(
            listOf(CredentialSpec("passphrase", "Key passphrase", "Unlocks the PGP key.", checkable = true)),
            ExtensionProtocol.describe(declaring(credential(extra = ",\"checkable\":true"))).descriptor!!.credentials,
        )
        ExtensionProtocol.describe(declaring())
        ExtensionProtocol.describe(declaring(*(1..4).map { credential("c$it") }.toTypedArray()))
        listOf(
            declaring(*(1..5).map { credential("c$it") }.toTypedArray()),
            declaring(credential(), credential()),
            declaring(credential(name = "Passphrase")),
            declaring(credential(name = "_passphrase")),
            declaring(credential(name = "a".repeat(33))),
            declaring(credential(kind = "token")),
            declaring(credential(title = "")),
            declaring(credential(title = "t".repeat(121))),
            declaring(credential(description = "d".repeat(501))),
            declaring("""{"name":"passphrase","title":"T","description":"D"}"""),
            declaring("""{"name":"passphrase","title":"T","description":"D","kind":"password","value":"x"}"""),
            declaring(credential(extra = ",\"checkable\":\"yes\"")),
            describe().replace("\"title\":\"Agenda\",", "\"title\":\"Agenda\",\"credentials\":{},"),
        ).forEach { assertThrows(it, Exception::class.java) { ExtensionProtocol.describe(it) } }
    }

    @Test
    fun `a completed reply may carry bounded named secrets and any violation is malformed`() {
        val envelope = """"protocolVersion":1,"reasonCode":null,"truncated":false,"content":[]"""

        fun reply(
            secrets: String,
            status: String = "completed",
        ) = """{$envelope,"status":"$status","secrets":$secrets}"""

        fun secret(
            value: String = "hunter2",
            label: String = "Password for github.com/me",
        ) = """{"value":${JsonPrimitive(value)},"label":${JsonPrimitive(label)}}"""

        val parsed = ExtensionProtocol.executeResult(reply("""{"password":${secret()},"otp_2":${secret("123456", "Code")}}"""))
        assertEquals(setOf("password", "otp_2"), parsed.secrets.keys)
        assertEquals("hunter2", parsed.secrets.getValue("password").value)
        assertFalse(parsed.secrets.toString().contains("hunter2"))
        assertEquals(parsed, ExtensionProtocol.executeResult(ExtensionProtocol.encodeResult(parsed)))
        ExtensionProtocol.executeResult(reply("""{"p":${secret("x".repeat(4096), "l".repeat(120))}}"""))

        val nine = (1..9).joinToString(",", "{", "}") { "\"s$it\":${secret()}" }
        listOf(
            reply("""{"password":${secret()}}""", "not_executed"),
            reply("""{"password":${secret()}}""", "failed"),
            reply("""{"password":${secret()}}""", "handed_off"),
            reply("""{"password":${secret()}}""", "unknown"),
            reply("{}"),
            reply("null"),
            reply(nine),
            reply("""{"Password":${secret()}}"""),
            reply("""{"1password":${secret()}}"""),
            reply("""{"pass-word":${secret()}}"""),
            reply("""{"${"a".repeat(33)}":${secret()}}"""),
            reply("""{"password":${secret(value = "")}}"""),
            reply("""{"password":${secret(value = "x".repeat(4097))}}"""),
            reply("""{"password":${secret(label = "")}}"""),
            reply("""{"password":${secret(label = "l".repeat(121))}}"""),
            reply("""{"password":{"value":"hunter2"}}"""),
            reply("""{"password":{"value":"hunter2","label":"l","hint":"x"}}"""),
            reply("""{"password":{"value":7,"label":"l"}}"""),
            reply("""{"password":"hunter2"}"""),
        ).forEach { assertThrows(it, Exception::class.java) { ExtensionProtocol.executeResult(it) } }
        val large = reply("""{"p":${secret("x".repeat(4000))}}""")
        assertThrows(Exception::class.java) { ExtensionProtocol.executeResult(large, maxBytes = large.length - 1) }
    }

    @Test
    fun `execution accepts only mode foreground wait and call ending while contradictions are rejected`() {
        val minimal = capability.replace(",\"maxWaitMillis\":30000", "")
        val parsed =
            ExtensionProtocol
                .describe(describe(minimal))
                .descriptor!!
                .capabilities
                .single()
        assertEquals(ExtensionProtocol.DEFAULT_WAIT_MILLIS, parsed.maxWaitMillis)
        assertEquals(CallEnding.NEVER, parsed.endsVoiceCall)
        val ending = capability.replace("\"maxWaitMillis\":30000", "\"maxWaitMillis\":30000,\"endsVoiceCall\":\"after_reply\"")
        assertEquals(
            CallEnding.AFTER_REPLY,
            ExtensionProtocol
                .describe(describe(ending))
                .descriptor!!
                .capabilities
                .single()
                .endsVoiceCall,
        )
        listOf(
            describe(ending.replace("after_reply", "later")),
            describe(capability.replace("\"maxWaitMillis\":30000", "\"maxWaitMillis\":30000,\"cancellation\":\"none\"")),
            describe(capability.replace("\"effects\"", "\"title\":\"Legacy\",\"effects\"")),
            describe().replace("\"protocolVersion\":1", "\"protocolVersion\":2"),
            describe(capability.replace("\"effects\":\"read\",", "")),
            describe(capability.replace("\"read\"", "\"harmless\"")),
            describe(capability.replace("\"requiresForeground\":false", "\"requiresForeground\":true")),
            describe(capability.replace("\"mode\":\"synchronous\"", "\"mode\":\"handoff\"")),
            describe(capability.replace("30000", "60001")),
            describe(capability.replace("16384", "16385")),
            describe(capability.replace("\"readOnlyHint\":true", "\"readOnlyHint\":false")),
            describe(capability.replace("\"effects\":\"read\"", "\"effects\":\"write\"")),
            describe(capability.replace("\"title\":\"Search agenda\",", "")),
            describe(capability.replace("\"result\":{\"maxBytes\":16384}", "\"result\":{\"mediaType\":\"text/plain\",\"maxBytes\":16384}")),
        ).forEach { payload -> assertThrows(payload, Exception::class.java) { ExtensionProtocol.describe(payload) } }
        val handoff = capability.replace("\"effects\":\"read\"", "\"effects\":\"external_handoff\"").replace("\"readOnlyHint\":true,", "")
        assertEquals(
            Effect.HANDOFF,
            ExtensionProtocol
                .describe(describe(handoff))
                .descriptor!!
                .capabilities
                .single()
                .effect,
        )
    }

    @Test
    fun `duplicate and namespaced capabilities are rejected atomically`() {
        listOf(describe("$capability,$capability"), describe(capability.replace("\"search\"", "\"eva.android.sms\"")), describe(""))
            .forEach { assertThrows(Exception::class.java) { ExtensionProtocol.describe(it) } }
    }

    @Test
    fun `wire parsing rejects duplicate escaped keys invalid unicode and trailing content`() {
        listOf(
            """{"a":1,"a":2}""",
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
        ) { ExtensionProtocol.encodeResult(reply.copy(outcome = reply.outcome.copy(message = "a".repeat(ExtensionProtocol.RESULT_BYTES)))) }
    }

    @Test
    fun `encoding restores scalar and list types and rejects unrecognized arguments`() {
        val payload =
            ExtensionProtocol.encodeArguments(
                schema,
                mapOf("query" to "taxes", "limit" to "3", "overdue" to "true", "tags" to """["work","home"]"""),
            )
        val parsed = ExtensionProtocol.arguments(schema, payload)
        assertFalse((parsed.getValue("limit") as JsonPrimitive).isString)
        assertEquals(JsonPrimitive(true), parsed["overdue"])
        assertEquals(JsonArray(listOf(JsonPrimitive("work"), JsonPrimitive("home"))), parsed["tags"])
        listOf(
            """{"query":"tax","limit":"3"}""",
            """{"query":null}""",
            """{"query":"tax","extra":true}""",
            """{"query":"tax","limit":51}""",
            """{"query":"tax","tags":"work"}""",
            """{"query":"tax","tags":[1]}""",
            """{"query":"tax","tags":["a","b","c","d","e"]}""",
        ).forEach { assertThrows(it, Exception::class.java) { ExtensionProtocol.arguments(schema, it) } }
    }

    @Test
    fun `flat schema rejects nesting unsafe integers duplicate enums and defaults`() {
        val prefix = """{"type":"object","properties":{"value":"""
        val suffix = """},"required":[],"additionalProperties":false}"""
        listOf(
            """{"type":"object","properties":{},"required":[],"additionalProperties":false}""",
            """{"type":"array"}""",
            """{"type":"array","items":{"type":"object","properties":{},"required":[],"additionalProperties":false}}""",
            """{"type":"array","items":{"type":"array","items":{"type":"string"}}}""",
            """{"type":"string","default":"a"}""",
            """{"type":"integer","maximum":9007199254740992}""",
            """{"type":"number","enum":[1,1.0]}""",
        ).forEach {
            assertThrows(
                it,
                Exception::class.java,
            ) { ExtensionProtocol.checkSchema(Json.parseToJsonElement(prefix + it + suffix).jsonObject) }
        }
        val integers = Json.parseToJsonElement(prefix + """{"type":"integer"}""" + suffix).jsonObject
        listOf("9007199254740992", "9007199254740991.1").forEach {
            assertThrows(Exception::class.java) { ExtensionProtocol.arguments(integers, """{"value":$it}""") }
        }
        val integerList = Json.parseToJsonElement(prefix + """{"type":"array","items":{"type":"integer"}}""" + suffix).jsonObject
        assertThrows(Exception::class.java) { ExtensionProtocol.arguments(integerList, """{"value":[9007199254740992]}""") }
    }

    @Test
    fun `terminal replies round trip content and structured data without upgrading handoff or uncertainty`() {
        val data = Json.parseToJsonElement("""{"items":[{"id":"a","title":"Taxes","due":"2026-09-15"}]}""").jsonObject
        listOf(
            InvocationStatus.COMPLETED,
            InvocationStatus.NOT_EXECUTED,
            InvocationStatus.FAILED,
            InvocationStatus.HANDED_OFF,
            InvocationStatus.UNKNOWN,
        ).forEach {
            val reply = ResultReply(ExecutionOutcome(it, "provider reports", data), null, true)
            assertEquals(reply, ExtensionProtocol.executeResult(ExtensionProtocol.encodeResult(reply)))
        }
        val uncertain = ResultReply(ExecutionOutcome(InvocationStatus.UNKNOWN, "May have executed"), "deadline_exceeded", false)
        assertEquals(uncertain, ExtensionProtocol.executeResult(ExtensionProtocol.encodeResult(uncertain)))
        listOf("stale_descriptor", "not_configured", "busy", "invalid_arguments", "unauthorized_caller").forEach {
            assertThrows(Exception::class.java) { ExtensionProtocol.encodeResult(uncertain.copy(reasonCode = it)) }
        }
        assertThrows(Exception::class.java) { ExtensionProtocol.encodeResult(uncertain.copy(reasonCode = "retry_me")) }
        val envelope = """"protocolVersion":1,"status":"completed","reasonCode":null,"truncated":false"""
        val blocks =
            ExtensionProtocol.executeResult(
                """{$envelope,"content":[{"type":"text","text":"one"},{"type":"text","text":"two"}]}""",
            )
        assertEquals("one\ntwo", blocks.outcome.message)
        assertNull(blocks.outcome.data)
        listOf(
            """{$envelope,"content":[{"type":"image","data":"...","mimeType":"image/png"}]}""",
            """{$envelope,"content":"text"}""",
            """{$envelope,"content":[],"message":"legacy"}""",
            """{$envelope,"content":[],"structuredContent":[1]}""",
            """{"protocolVersion":1,"status":"accepted","reasonCode":null,"truncated":false,"content":[]}""",
        ).forEach { assertThrows(it, Exception::class.java) { ExtensionProtocol.executeResult(it) } }
    }

    @Test
    fun `structured content must satisfy a declared output schema`() {
        val output = Json.parseToJsonElement(outputSchema).jsonObject
        val envelope = """"protocolVersion":1,"status":"completed","reasonCode":null,"truncated":false,"content":[]"""
        val valid = """{$envelope,"structuredContent":{"items":[{"id":"a","extra":true}]}}"""
        assertEquals(1, (ExtensionProtocol.executeResult(valid, outputSchema = output).outcome.data!!["items"] as JsonArray).size)
        listOf(
            """{$envelope,"structuredContent":{"items":[{"title":"missing id"}]}}""",
            """{$envelope,"structuredContent":{"items":{}}}""",
            """{$envelope,"structuredContent":{"other":[]}}""",
        ).forEach { assertThrows(it, Exception::class.java) { ExtensionProtocol.executeResult(it, outputSchema = output) } }
        ExtensionProtocol.executeResult("""{$envelope,"structuredContent":{"other":[]}}""")
    }

    @Test
    fun `failed discovery never returns a partial descriptor`() {
        val reply =
            ExtensionProtocol.describe(
                """{"protocolVersion":1,"status":"not_executed","reasonCode":"busy","truncated":false,
                "content":[{"type":"text","text":"Busy"}],"descriptor":null}""",
            )
        assertNull(reply.descriptor)
        assertEquals("busy", reply.result.reasonCode)
        assertEquals("Busy", reply.result.outcome.message)
        assertThrows(Exception::class.java) { ExtensionProtocol.describe(describe().replace("\"truncated\":false", "\"truncated\":true")) }
        assertTrue(reply.result.outcome.status == InvocationStatus.NOT_EXECUTED)
    }

    @Test
    fun `a failed call may name the delivered credentials it rejected and nothing else may`() {
        val envelope = """"protocolVersion":1,"truncated":false,"content":[{"type":"text","text":"Wrong passphrase."}]"""

        fun reply(
            rejected: String,
            status: String = "not_executed",
            reason: String = "\"not_configured\"",
        ) = """{$envelope,"status":"$status","reasonCode":$reason,"rejectedCredentials":$rejected}"""

        val parsed = ExtensionProtocol.executeResult(reply("""["passphrase","pin"]"""))
        assertEquals(setOf("passphrase", "pin"), parsed.rejectedCredentials)
        assertEquals(parsed, ExtensionProtocol.executeResult(ExtensionProtocol.encodeResult(parsed)))
        assertEquals(
            setOf("passphrase"),
            ExtensionProtocol.executeResult(reply("""["passphrase"]""", "failed", "null")).rejectedCredentials,
        )
        listOf(
            reply("""["passphrase"]""", "completed", "null"),
            reply("""["passphrase"]""", "unknown", "null"),
            reply("[]"),
            reply("""["passphrase","passphrase"]"""),
            reply("""["Passphrase"]"""),
            reply("""["a","b","c","d","e"]"""),
            reply("\"passphrase\""),
        ).forEach { assertThrows(it, Exception::class.java) { ExtensionProtocol.executeResult(it) } }
    }

    @Test
    fun `a check reply is a verdict, or an explanation of why the provider could not check`() {
        fun reply(
            status: String,
            text: String,
            tail: String = "",
        ) = """{"protocolVersion":1,"status":"$status","reasonCode":${if (status == "completed") "null" else "\"not_configured\""},""" +
            """"truncated":false,"content":[{"type":"text","text":"$text"}]$tail}"""

        assertEquals(
            CredentialVerdict.Accepted("It unlocks the key."),
            ExtensionProtocol.checkResult(reply("completed", "It unlocks the key.", ""","structuredContent":{"verdict":"accepted"}""")),
        )
        assertEquals(
            CredentialVerdict.Rejected("It does not unlock the key."),
            ExtensionProtocol.checkResult(
                reply("completed", "It does not unlock the key.", ""","structuredContent":{"verdict":"rejected"}"""),
            ),
        )
        assertEquals(
            CredentialVerdict.Unverified("No key is imported."),
            ExtensionProtocol.checkResult(reply("not_executed", "No key is imported.")),
        )
        assertEquals(
            """{"_credentials":{"passphrase":"correct horse"}}""",
            ExtensionProtocol.checkArguments("passphrase", "correct horse"),
        )
        listOf(
            reply("completed", "ok"),
            reply("completed", "ok", ""","structuredContent":{"verdict":"maybe"}"""),
            reply("completed", "ok", ""","structuredContent":{"verdict":"accepted","note":"x"}"""),
            reply("not_executed", "no", ""","rejectedCredentials":["passphrase"]"""),
        ).forEach { assertThrows(it, Exception::class.java) { ExtensionProtocol.checkResult(it) } }
    }
}
