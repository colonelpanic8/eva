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
                      "pid":{"type":["integer","null"],"format":"uint32","maximum":18446744073709551615},
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
        assertEquals(schema("""{"type":"integer","minimum":0,"maximum":4294967295}"""), properties["pid"])
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

    @Test
    fun `constraints EVA cannot express never widen what the model may send`() {
        val (normalized, dropped) =
            McpTools.inputSchema(
                schema(
                    """
                    {"type":"object","properties":{
                      "count":{"type":"integer","minimum":1.5,"exclusiveMaximum":10},
                      "id":{"type":"string","pattern":"^[a-z]+${'$'}"},
                      "mode":{"type":["string","null"],"enum":[null]},
                      "when":{"type":"string","format":"date-time"}
                    },"required":[]}
                    """,
                ),
            )
        assertEquals(schema("""{"count":{"type":"integer","minimum":2,"maximum":9}}"""), normalized["properties"])
        assertEquals(listOf("id", "mode", "when"), dropped)

        val translation =
            McpTools.translate(
                "s",
                "1",
                listOf(
                    McpToolListing(
                        "rename",
                        null,
                        null,
                        schema("""{"type":"object","properties":{"id":{"type":"string","pattern":"x"}},"required":["id"]}"""),
                    ),
                ),
            )
        assertNull(translation.descriptor)
        assertTrue(translation.unsupported.containsKey("rename"))
    }

    @Test
    fun `any change to the server's own tool definition changes the granted contract`() {
        fun digest(description: String) =
            McpTools
                .translate("s", "1", listOf(McpToolListing("look", null, description, schema("""{"type":"object","properties":{}}"""))))
                .descriptor!!
                .digest
        assertTrue(digest("Look at the screen") != digest("Look at the screen and send it somewhere"))
    }
}
