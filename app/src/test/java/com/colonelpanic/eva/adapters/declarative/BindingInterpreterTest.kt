package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BindingInterpreterTest {
    @Test
    fun `intent query and content selection never interpret argument syntax`() {
        val hostile = "x' OR 1=1 -- &other=y#fragment/😀"
        val intent = PackageCodec.decode(packageJson(intentBinding)).capabilities.single()
        val request = BindingArguments(intent, mapOf("title" to hostile)).intent(intent.binding as DeclarativeBinding.Intent)
        assertTrue(request.uri.startsWith("mova://capture?title=x%27%20OR%201%3D1"))
        assertFalse(request.uri.contains("&other"))
        assertFalse(request.uri.contains("#fragment"))
        val content = PackageCodec.decode(packageJson(contentBinding, "synchronous", false)).capabilities.single()
        val query = BindingArguments(content, mapOf("title" to hostile)).content(content.binding as DeclarativeBinding.Content)
        assertEquals("title = ?", query.selection)
        assertEquals(listOf(hostile), query.selectionArguments)
        assertEquals("content://example.todos/items", query.uri)
        assertThrows(Exception::class.java) { BindingArguments(content, emptyMap()).content(content.binding) }
    }

    @Test
    fun `HTTP capture constructs typed nested body without string substitution`() {
        val capability = PackageCodec.decode(packageJson(httpBinding, "synchronous", false)).capabilities.single()
        val title = "a\",\"admin\":true,\"other\":\"b"
        val request = BindingArguments(capability, mapOf("title" to title)).http(capability.binding as DeclarativeBinding.Http)
        assertEquals("https://agenda.example.org/capture", request.url)
        val body =
            kotlinx.serialization.json.Json
                .parseToJsonElement(request.body!!)
        assertEquals(
            JsonObject(mapOf("template" to JsonPrimitive("default"), "values" to JsonObject(mapOf("Title" to JsonPrimitive(title))))),
            body,
        )
    }

    @Test
    fun `write completion requires exact evidence and accepted status never becomes completed`() {
        val capability = PackageCodec.decode(packageJson(httpBinding, "synchronous", false)).capabilities.single()
        assertEquals(InvocationStatus.COMPLETED, BindingResults.http(capability, HttpResponse(200, """{"status":"created"}""")).status)
        assertEquals(InvocationStatus.UNKNOWN, BindingResults.http(capability, HttpResponse(200, """{"status":"queued"}""")).status)
        assertEquals(InvocationStatus.UNKNOWN, BindingResults.http(capability, HttpResponse(202, """{"status":"created"}""")).status)
        assertEquals(InvocationStatus.UNKNOWN, BindingResults.http(capability, HttpResponse(500, """{"status":"created"}""")).status)
        val noEvidence =
            capability.copy(
                binding =
                    (capability.binding as DeclarativeBinding.Http).let {
                        it.copy(result = it.result.copy(evidence = null))
                    },
            )
        assertEquals(InvocationStatus.UNKNOWN, BindingResults.http(noEvidence, HttpResponse(200, "{}")).status)
    }

    @Test
    fun `results project approved fields and report truncation without leaking other content columns`() {
        val binding =
            PackageCodec
                .decode(
                    packageJson(contentBinding, "synchronous", false),
                ).capabilities
                .single()
                .binding as DeclarativeBinding.Content
        val row = JsonObject(mapOf("title" to JsonPrimitive("😀".repeat(300)), "secret" to JsonPrimitive("hidden")))
        val result = BindingResults.content(binding.copy(maxBytes = 7), ContentRows(listOf(row), true))
        assertEquals(InvocationStatus.COMPLETED, result.status)
        assertTrue(result.message.contains("truncated"))
        assertFalse(result.message.contains("hidden"))
        assertFalse(result.message.contains('�'))
        assertThrows(Exception::class.java) {
            BindingResults.content(binding, ContentRows(listOf(JsonObject(mapOf("title" to JsonPrimitive(42)))), false))
        }
    }
}
