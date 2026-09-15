package com.colonelpanic.eva.providers

import com.colonelpanic.eva.capability.BoundedJson
import com.colonelpanic.eva.capability.CapabilitySource
import com.colonelpanic.eva.capability.ReceiptProvenance
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultContentTest {
    private val source = ReceiptProvenance(CapabilitySource("fixture.component", "Fixture"), "binding")
    private val call = CallIdentity("epoch", "session", "input", "generation", "turn", "revision", "call")

    @Test
    fun `live results preserve source in relay supported evidence without upgrading status`() {
        val result = CorrelatedToolResult(call, "UNKNOWN", "a".repeat(MODEL_RESULT_CHARS - 1) + "😀", provenance = source).wireOutcome()
        assertEquals("UNKNOWN", result.getValue("status").jsonPrimitive.content)
        assertEquals(source.toJson(), result.getValue("evidence").jsonObject["provenance"])
        val message = result.getValue("message").jsonPrimitive.content
        assertTrue(BoundedJson.validUnicode(message))
        assertTrue(message.length <= MODEL_RESULT_CHARS)
        assertTrue(message.endsWith(MODEL_TRUNCATION_NOTE))
    }

    @Test
    fun `results within the extension budget reach the model whole with their structured data`() {
        val note = "\n[Truncated] Narrow the search."
        val text = "x".repeat(MODEL_RESULT_CHARS - note.length) + note
        val data = JsonObject(mapOf("items" to JsonPrimitive(1)))
        val result = CorrelatedToolResult(call, "COMPLETED", text, data, source).wireOutcome()
        assertEquals(text, result.getValue("message").jsonPrimitive.content)
        assertEquals(data, result["data"])
        val oversized = JsonObject(mapOf("blob" to JsonPrimitive("y".repeat(MODEL_RESULT_CHARS))))
        val omitted = CorrelatedToolResult(call, "COMPLETED", "short", oversized).wireOutcome()
        assertNull(omitted["data"])
        assertTrue(omitted.containsKey("dataOmitted"))
    }
}
