package com.colonelpanic.eva.providers

import com.colonelpanic.eva.capability.BoundedJson
import com.colonelpanic.eva.capability.CapabilitySource
import com.colonelpanic.eva.capability.ReceiptProvenance
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultContentTest {
    @Test
    fun `live results preserve source in relay supported evidence without upgrading status`() {
        val source = ReceiptProvenance(CapabilitySource("fixture.component", "Fixture"), "binding")
        val call = CallIdentity("epoch", "session", "input", "generation", "turn", "revision", "call")
        val result = CorrelatedToolResult(call, "UNKNOWN", "a".repeat(1999) + "😀", provenance = source).wireOutcome()
        assertEquals("UNKNOWN", result.getValue("status").jsonPrimitive.content)
        assertEquals(source.toJson(), result.getValue("evidence").jsonObject["provenance"])
        val message = result.getValue("message").jsonPrimitive.content
        assertTrue(BoundedJson.validUnicode(message))
        assertEquals(1999, message.length)
    }
}
