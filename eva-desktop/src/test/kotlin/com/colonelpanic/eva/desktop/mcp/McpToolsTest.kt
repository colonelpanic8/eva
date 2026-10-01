package com.colonelpanic.eva.desktop.mcp

import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.extensions.Effect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolsTest {
    private fun schema(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `nullable optional parameters become plain optional ones and unusable optional ones are left out`() {
        val (normalized, dropped) =
            McpTools.inputSchema(
                schema(
                    """
                    {"type":"object","${'$'}schema":"x","properties":{
                      "title":{"type":["string","null"],"description":"Window title","default":null},
                      "pid":{"type":["integer","null"],"format":"uint32","minimum":0,"maximum":18446744073709551615},
                      "keys":{"type":"array","items":{"type":"string"}},
                      "format":{"description":"no type at all"},
                      "region":{"type":"object","properties":{"x":{"type":"integer"}}},
                      "text":{"type":"string"}
                    },"required":["text"]}
                    """,
                ),
            )
        val properties = normalized["properties"] as JsonObject
        assertEquals(listOf("title", "pid", "keys", "text"), properties.keys.toList())
        assertEquals(schema("""{"type":"string","description":"Window title"}"""), properties["title"])
        assertEquals(JsonPrimitive(9_007_199_254_740_991), (properties["pid"] as JsonObject)["maximum"])
        assertEquals(listOf("format", "region"), dropped)
        assertEquals(Json.parseToJsonElement("""["text"]"""), normalized["required"])
    }

    @Test
    fun `a tool whose required parameter cannot be expressed is offered as unavailable, and the rest still work`() {
        val translation =
            McpTools.translate(
                "computer-use",
                "0.4.1",
                listOf(
                    McpToolListing("list_windows", null, "List windows", schema("""{"type":"object"}""")),
                    McpToolListing(
                        "drag",
                        "Drag",
                        "Drag the pointer",
                        schema("""{"type":"object","properties":{"path":{"type":"object"}},"required":["path"]}"""),
                    ),
                ),
            )
        val descriptor = checkNotNull(translation.descriptor)
        assertEquals(listOf("list_windows"), descriptor.capabilities.map { it.name })
        assertEquals(Effect.UNKNOWN, descriptor.capabilities.single().effect)
        assertTrue(translation.unsupported.getValue("drag").contains("path"))
    }

    @Test
    fun `replies keep the tool's own failure apart from success and say what they leave out`() {
        val failed = McpTools.outcome(McpCallReply(listOf("no such window"), 0, null, isError = true))
        assertEquals(InvocationStatus.FAILED, failed.status)

        val done = McpTools.outcome(McpCallReply(listOf("ok"), 2, buildJsonObject { put("windows", JsonPrimitive(1)) }, isError = false))
        assertEquals(InvocationStatus.COMPLETED, done.status)
        assertTrue(done.message.contains("2 non-text result blocks"))
        assertEquals(JsonPrimitive(1), done.data?.get("windows"))

        val huge = buildJsonObject { put("blob", JsonPrimitive("x".repeat(20_000))) }
        assertNull(McpTools.outcome(McpCallReply(emptyList(), 0, huge, isError = false)).data)
    }
}
