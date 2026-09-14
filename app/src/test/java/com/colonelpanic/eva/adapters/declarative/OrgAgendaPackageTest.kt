package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OrgAgendaPackageTest {
    private val definition =
        PackageCodec.decode(
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .map { File(it, "docs/examples/org-agenda.json") }
                .first { it.isFile }
                .readText(),
        )

    private fun capability(name: String) = definition.capabilities.single { it.name == name }

    private fun request(
        name: String,
        args: Map<String, String>,
    ): Pair<PackageCapability, HttpRequest> {
        val capability = capability(name)
        val arguments = BindingArguments(capability, args)
        val binding = arguments.select(capability.binding) as DeclarativeBinding.Http
        return capability.copy(binding = binding) to arguments.http(binding)
    }

    @Test
    fun `proving package supports capture defaults explicit templates and strict completion alternatives`() {
        val capture = request("capture", mapOf("title" to "Test title")).second
        val body = Json.parseToJsonElement(capture.body!!).jsonObject
        assertEquals(JsonPrimitive("default"), body["template"])
        assertEquals(JsonObject(mapOf("Title" to JsonPrimitive("Test title"))), body["values"])
        assertEquals(
            JsonPrimitive("work"),
            Json
                .parseToJsonElement(
                    request("capture", mapOf("title" to "Test", "template" to "work")).second.body!!,
                ).jsonObject["template"],
        )
        val (complete, byId) = request("complete_todo", mapOf("id" to "stable-id", "file" to "ignored", "pos" to "1", "title" to "ignored"))
        assertEquals(
            JsonObject(mapOf("id" to JsonPrimitive("stable-id"), "strict" to JsonPrimitive(true))),
            Json.parseToJsonElement(byId.body!!),
        )
        val byPosition = request("complete_todo", mapOf("file" to "/todos.org", "pos" to "42", "title" to "Test title")).second
        assertEquals(JsonPrimitive(true), Json.parseToJsonElement(byPosition.body!!).jsonObject["strict"])
        assertEquals(JsonPrimitive(42), Json.parseToJsonElement(byPosition.body).jsonObject["pos"])
        assertThrows(Exception::class.java) { request("complete_todo", mapOf("title" to "Test title")) }
        assertThrows(Exception::class.java) { request("complete_todo", mapOf("file" to "/todos.org", "pos" to "42")) }
        assertEquals(
            InvocationStatus.NOT_EXECUTED,
            BindingResults
                .http(
                    complete,
                    HttpResponse(
                        409,
                        """{"status":"error","code":"strict_lookup_conflict","message":"Stale reference","foundTitle":null}""",
                    ),
                ).status,
        )
    }

    @Test
    fun `search lines retain exact completion references and distinguish server truncation`() {
        val (search, request) = request("search_todos", mapOf("q" to "rent & bills"))
        assertTrue(request.url.contains("q=rent%20%26%20bills&limit=20"))
        val item = """{"todo":"TODO","title":"Pay rent\ncarefully","priority":"A","scheduled":{"date":"2026-09-15"},
            "deadline":{"date":"2026-09-16"},"tags":["home","money"],"id":null,"file":"/home/me/todos.org","pos":42}"""
        val result = BindingResults.http(search, HttpResponse(200, """{"defaults":{},"todos":[$item],"total":3}"""))
        assertEquals(InvocationStatus.COMPLETED, result.status)
        assertTrue(result.message.contains("state=\"TODO\" priority=\"A\" title=\"Pay rent\\ncarefully\""))
        assertTrue(result.message.contains("tags=[\"home\",\"money\"] id=null file=\"/home/me/todos.org\" pos=42"))
        assertTrue(result.message.contains("[Truncated]"))
        val completeResult = BindingResults.http(search, HttpResponse(200, """{"todos":[$item],"total":1}"""))
        assertFalse(completeResult.message.contains("[Truncated]"))
        assertEquals(2, result.message.lines().size)
    }

    @Test
    fun `agenda handles grouped days and custom views select fixed list or run routes`() {
        val (agenda, _) = request("agenda", mapOf("span" to "week"))
        val response = """{"days":{"2026-09-14":[{"todo":"TODO","title":"One"}],"2026-09-15":[{"todo":"NEXT","title":"Two"}]}}"""
        val result = BindingResults.http(agenda, HttpResponse(200, response))
        assertTrue(result.message.contains("title=\"One\""))
        assertTrue(result.message.contains("title=\"Two\""))
        val (list, listRequest) = request("custom_view", emptyMap())
        assertTrue(listRequest.url.endsWith("/custom-views"))
        assertEquals(
            "key=\"w\" name=\"Work\"",
            BindingResults.http(list, HttpResponse(200, """{"views":[{"key":"w","name":"Work"}]}""")).message,
        )
        assertTrue(request("custom_view", mapOf("key" to "w")).second.url.endsWith("/custom-view?key=w"))
    }

    @Test
    fun `mova create handoff encodes the title without injecting extra parameters`() {
        val capability = capability("open_create")
        val binding = capability.binding as DeclarativeBinding.Intent
        val request = BindingArguments(capability, mapOf("title" to "Pay rent & state=DONE?#")).intent(binding)
        assertEquals("mova://create?title=Pay%20rent%20%26%20state%3DDONE%3F%23", request.uri)
        assertEquals(com.colonelpanic.eva.capability.ExecutionMode.HANDOFF, capability.execution.mode)
        assertEquals(PackageEffect.HANDOFF, capability.effect)
    }

    @Test
    fun `byte and item limits emit whole lines with explicit truncation instead of broken identifiers`() {
        val source = capability("search_todos")
        val http = source.binding as DeclarativeBinding.Http
        val limited = source.copy(binding = http.copy(result = http.result.copy(maxBytes = 10)))
        val response = """{"todos":[{"todo":"TODO","title":"One","id":"very-long-id"}],"total":1}"""
        val result = BindingResults.http(limited, HttpResponse(200, response))
        assertFalse(result.message.contains("very-long"))
        assertTrue(result.message.contains("No complete item fits"))
        assertTrue(result.message.contains("[Truncated]"))
    }
}
