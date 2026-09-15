package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.ExecutionMode
import com.colonelpanic.eva.capability.ExecutionSemantics
import com.colonelpanic.eva.capability.InteractionMode
import com.colonelpanic.eva.capability.WaitBudget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun packageJson(
    binding: String,
    mode: String = "handoff",
    foreground: Boolean = true,
    effect: String = "read",
): String =
    """
    {
"formatVersion":1,"id":"community.example","version":"0.1.0","title":"Example",
"capabilities":[{
"tool":{"name":"capture","description":"Capture a title","inputSchema":{
"type":"object","properties":{"title":{"type":"string","maxLength":100}},"required":["title"],"additionalProperties":false}},
"title":"Capture","effects":"$effect","execution":{"mode":"$mode","requiresForeground":$foreground,
"maxWaitMillis":30000,"cancellation":"none","idempotency":"none","reconciliation":"none"},
"binding":$binding}]}
    """.trimIndent()

internal val intentBinding = """{"kind":"android.intent","action":"android.intent.action.VIEW",
"uri":{"base":"mova://create","query":{"title":{"argument":"title","type":"string"}}}}"""

internal val contentBinding = """{"kind":"android.content","authority":"example.todos","uri":"content://example.todos/items",
"projection":{"title":"string"},"selection":[{"column":"title","operator":"=","value":{"argument":"title","type":"string"}}],
"maxRows":10,"maxBytes":1000}"""

internal val httpBinding = """{"kind":"http","origin":"https://agenda.example.org","method":"POST","path":"/capture",
"parameters":[],"credential":"org-agenda","requestBody":{"fields":{
"template":{"type":"string","value":"default"},"values":{"fields":{"Title":{"type":"string","argument":"title"}}}}},
"maxResponseBytes":65536,"result":{"pointer":"","maxBytes":2000,"evidence":{"pointer":"/status","equals":"created"}}}"""

class PackageCodecTest {
    @Test
    fun `named validators restrict arguments and receipt data is bounded contract content`() {
        val json =
            packageJson(intentBinding).replace(
                "\"tool\":",
                "\"validators\":{\"title\":\"phoneNumber\"}," +
                    "\"receipts\":{\"success\":\"Opened dialer\",\"handlerMissing\":\"No dialer\"},\"tool\":",
            )
        val capability = PackageCodec.decode(json).capabilities.single()
        assertEquals("Opened dialer", capability.receipts.success)
        assertThrows(Exception::class.java) { BindingArguments(capability, mapOf("title" to "not a number")) }
        BindingArguments(capability, mapOf("title" to "+1 (415) 555-1234"))
        assertThrows(Exception::class.java) { PackageCodec.decode(json.replace("phoneNumber", "runScript")) }
        assertThrows(Exception::class.java) { PackageCodec.decode(json.replace("Opened dialer", "x".repeat(1001))) }
        assertNotEquals(PackageCodec.decode(json).digest, PackageCodec.decode(json.replace("No dialer", "Handler missing")).digest)
        assertTrue(NamedValidators.accepts("httpUrl", "https://example.org/path?q=x"))
        assertThrows(Exception::class.java) {
            BindingArguments(capability.copy(validators = mapOf("title" to "httpUrl")), mapOf("title" to "javascript:alert(1)"))
        }
        assertTrue(NamedValidators.accepts("emailAddress", "user@example.org"))
        assertEquals(false, NamedValidators.accepts("emailAddress", "user@example.org;other@example.org"))
    }

    @Test
    fun `MCP tool fields carry titles output schemas annotations and list arguments into JSON bodies`() {
        val output = """{"type":"object","properties":{"id":{"type":"string"}},"required":["id"],"additionalProperties":true}"""
        val body =
            httpBinding.replace(
                "\"values\":{\"fields\":{\"Title\":{\"type\":\"string\",\"argument\":\"title\"}}}",
                "\"values\":{\"fields\":{\"Title\":{\"type\":\"string\",\"argument\":\"title\"}}}," +
                    "\"tags\":{\"type\":\"array\",\"argument\":\"tags\",\"default\":[\"inbox\"]}",
            )
        val json =
            packageJson(body, "synchronous", false)
                .replace("\"title\":\"Capture\",", "")
                .replace(
                    "\"tool\":{\"name\":\"capture\",",
                    "\"tool\":{\"name\":\"capture\",\"title\":\"Capture todo\",\"outputSchema\":$output," +
                        "\"annotations\":{\"readOnlyHint\":false,\"destructiveHint\":false},",
                ).replace(
                    "\"title\":{\"type\":\"string\",\"maxLength\":100}",
                    "\"title\":{\"type\":\"string\",\"maxLength\":100},\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"maxItems\":3}",
                ).replace(",\"cancellation\":\"none\",\"idempotency\":\"none\",\"reconciliation\":\"none\"", "")
        val capability = PackageCodec.decode(json).capabilities.single()
        assertEquals("Capture todo", capability.title)
        assertEquals(JsonPrimitive(false), capability.annotations!!["readOnlyHint"])
        assertEquals("none", capability.execution.idempotency)
        assertTrue(capability.outputSchema!!.containsKey("properties"))
        val explicit = BindingArguments(capability, mapOf("title" to "Taxes", "tags" to """["work","home"]"""))
        val parsed = Json.parseToJsonElement(explicit.http(capability.binding as DeclarativeBinding.Http).body!!).jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("work"), JsonPrimitive("home"))), parsed["tags"])
        val defaulted = BindingArguments(capability, mapOf("title" to "Taxes")).http(capability.binding as DeclarativeBinding.Http)
        assertEquals(JsonArray(listOf(JsonPrimitive("inbox"))), Json.parseToJsonElement(defaulted.body!!).jsonObject["tags"])
        assertThrows(Exception::class.java) { BindingArguments(capability, mapOf("title" to "Taxes", "tags" to """["a","b","c","d"]""")) }
        assertNotEquals(
            PackageCodec.decode(json).digest,
            PackageCodec.decode(json.replace("\"destructiveHint\":false", "\"destructiveHint\":true")).digest,
        )
        assertThrows(Exception::class.java) { PackageCodec.decode(json.replace("\"readOnlyHint\":false", "\"readOnlyHint\":true")) }
        assertThrows(Exception::class.java) {
            PackageCodec
                .decode(json.replace("\"title\":\"Capture todo\",", "").replace("\"tool\":{", "\"title\":\"Legacy\",\"tool\":{"))
                .capabilities
                .single()
                .title
                .let { require(it == "Capture todo") }
        }
        assertThrows(Exception::class.java) { PackageCodec.decode(json.replace("\"title\":\"Capture todo\",", "")) }
        val queryList =
            json.replace(
                "\"parameters\":[]",
                "\"parameters\":[{\"in\":\"query\",\"name\":\"tags\",\"value\":{\"type\":\"array\",\"argument\":\"tags\"}}]",
            )
        assertThrows(Exception::class.java) { PackageCodec.decode(queryList) }
    }

    @Test
    fun `share request carries a visible app name without accepting a model supplied package`() {
        val binding = """{"kind":"android.intent","action":"android.intent.action.SEND","mimeType":"text/plain",
            "packageByName":"title","extras":{"android.intent.extra.TEXT":{"type":"string","value":"Untyped text"}}}"""
        val capability = PackageCodec.decode(packageJson(binding)).capabilities.single()
        val request = BindingArguments(capability, mapOf("title" to "Signal")).intent(capability.binding as DeclarativeBinding.Intent)
        assertEquals("Signal", request.appName)
        assertEquals(null, request.targetPackage)
        assertEquals("", request.uri)
        assertEquals("text/plain", request.mimeType)
        assertThrows(Exception::class.java) {
            PackageCodec.decode(packageJson(binding.replace("\"packageByName\":", "\"package\":\"example.app\",\"packageByName\":")))
        }
    }

    @Test
    fun `intent and HTTP effect floors cannot be reduced by a read claim`() {
        val intent = PackageCodec.decode(packageJson(intentBinding)).capabilities.single()
        assertEquals(PackageEffect.HANDOFF, intent.effect)
        assertEquals(ExecutionMode.HANDOFF, intent.execution.mode)
        val http = PackageCodec.decode(packageJson(httpBinding, "synchronous", false)).capabilities.single()
        assertEquals(PackageEffect.WRITE, http.effect)
        assertEquals("org-agenda", (http.binding as DeclarativeBinding.Http).credential)
        assertEquals(
            PackageEffect.UNKNOWN,
            PackageCodec
                .decode(packageJson(intentBinding, effect = "unknown"))
                .capabilities
                .single()
                .effect,
        )
    }

    @Test
    fun `content queries retain fixed authority typed columns bound predicates and limits`() {
        val binding =
            PackageCodec
                .decode(
                    packageJson(contentBinding, "synchronous", false),
                ).capabilities
                .single()
                .binding as DeclarativeBinding.Content
        assertEquals(mapOf("title" to "string"), binding.projection)
        assertEquals(ScalarSlot.Argument("title", "string"), binding.selection.single().slot)
        listOf(
            contentBinding.replace("content://example.todos", "content://other.todos"),
            contentBinding.replace("\"column\":\"title\"", "\"column\":\"title OR 1=1\""),
            contentBinding.replace("\"operator\":\"=\"", "\"operator\":\"= ? OR 1\""),
            contentBinding.replace("\"maxRows\":10", "\"maxRows\":101"),
            contentBinding.replace("\"projection\":{\"title\":\"string\"}", "\"projection\":{\"title\":\"blob\"}"),
        ).forEach { invalid -> assertThrows(Exception::class.java) { PackageCodec.decode(packageJson(invalid, "synchronous", false)) } }
    }

    @Test
    fun `unsupported schema authority fields credentials and execution promises fail closed`() {
        listOf(
            packageJson(intentBinding).replace("\"type\":\"string\",\"maxLength\":100", "\"type\":\"array\""),
            packageJson(intentBinding.replace("\"kind\":", "\"flags\":1,\"kind\":")),
            packageJson(intentBinding.replace("mova://create", "intent://capture")),
            packageJson(intentBinding.replace("\"argument\":\"title\",\"type\":\"string\"", "\"argument\":\"title\",\"type\":\"integer\"")),
            packageJson(intentBinding).replace("\"reconciliation\":\"none\"", "\"reconciliation\":\"poll\""),
            packageJson(intentBinding).replace("\"mode\":\"handoff\"", "\"mode\":\"synchronous\""),
            packageJson(httpBinding.replace("https://agenda.example.org", "https://secret@agenda.example.org"), "synchronous", false),
            packageJson(httpBinding.replace("org-agenda", "openai.apiKey"), "synchronous", false),
            packageJson(httpBinding.replace("/capture", "/../capture"), "synchronous", false),
            packageJson(
                httpBinding.replace(
                    "\"parameters\":[]",
                    "\"parameters\":[{\"in\":\"header\",\"name\":\"Authorization\",\"value\":{\"type\":\"string\",\"argument\":\"title\"}}]",
                ),
                "synchronous",
                false,
            ),
        ).forEach { invalid -> assertThrows(Exception::class.java) { PackageCodec.decode(invalid) } }
    }

    @Test
    fun `digest covers bindings and effects and preserves MCP tool fields`() {
        val packageDefinition = PackageCodec.decode(packageJson(intentBinding))
        val reordered =
            JsonObject(
                packageDefinition.document.entries
                    .reversed()
                    .associate { it.toPair() },
            )
        assertEquals(packageDefinition.digest, PackageCodec.decode(reordered.toString()).digest)
        assertNotEquals(
            packageDefinition.digest,
            PackageCodec.decode(packageJson(intentBinding.replace("mova://create", "other://capture"))).digest,
        )
        assertNotEquals(packageDefinition.digest, PackageCodec.decode(packageJson(intentBinding, effect = "unknown")).digest)
        assertThrows(
            Exception::class.java,
        ) { PackageCodec.decode(packageJson(intentBinding).replace("\"id\":", "\"id\":\"other.id\",\"id\":")) }
    }

    @Test
    fun `wait precedence allows shorter and longer overrides with a non overridable ceiling`() {
        val voice = WaitBudget(InteractionMode.VOICE, WaitBudget.defaultMillis(InteractionMode.VOICE), null, null)
        assertEquals(20_000L, voice.effectiveMillis)
        val packageDefault = voice.copy(capabilityDefaultMillis = 30_000)
        assertEquals(30_000L, packageDefault.effectiveMillis)
        assertEquals(5_000L, packageDefault.copy(instanceOverrideMillis = 5_000).effectiveMillis)
        val override = packageDefault.copy(instanceOverrideMillis = 90_000)
        assertEquals(60_000L, override.effectiveMillis)
        assertTrue(override.receipt().contains("extension=90000, capability=30000, voice=20000"))
        assertTrue(override.receipt().contains("clamped"))
        assertEquals(50_000L, voice.copy(modeDefaultMillis = 50_000).effectiveMillis)
        assertThrows(Exception::class.java) { voice.copy(instanceOverrideMillis = 0) }
        assertThrows(Exception::class.java) { ExecutionSemantics(ExecutionMode.SYNCHRONOUS, false, cancellation = "signal") }
        val longDefault = PackageCodec.decode(packageJson(intentBinding).replace("30000", "90000"))
        assertEquals(
            90_000L,
            longDefault.capabilities
                .single()
                .execution.maxWaitMillis,
        )
    }
}
