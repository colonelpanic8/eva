package com.colonelpanic.eva.capability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolSchemaTest {
    @Test
    fun `nested objects enforce required fields closed properties and scalar bounds`() {
        val schema =
            Json
                .parseToJsonElement(
                    """{
            "type":"object","additionalProperties":false,"required":["target"],"properties":{
                "target":{"type":"object","additionalProperties":false,"required":["count","mode"],"properties":{
                    "count":{"type":"integer","minimum":1,"maximum":3},
                    "mode":{"type":"string","enum":["walk","drive"]}
                }}
            }}""",
                ).jsonObject
        ToolSchema.check(schema)
        assertNull(ToolSchema.error(schema, Json.parseToJsonElement("""{"target":{"count":2,"mode":"walk"}}""")))
        for (args in listOf(
            """{"target":{"count":4,"mode":"walk"}}""",
            """{"target":{"count":1.5,"mode":"walk"}}""",
            """{"target":{"count":2,"mode":"fly"}}""",
            """{"target":{"count":2,"mode":"walk","hidden":true}}""",
            """{"target":{"count":2}}""",
            """{"target":null}""",
        )) {
            assertNotNull(ToolSchema.error(schema, Json.parseToJsonElement(args)))
        }
    }

    @Test
    fun `nullable types open objects and object arrays fail for inputs`() {
        for (schema in listOf(
            """{"type":["string","null"]}""",
            """{"type":"object","properties":{},"required":[],"additionalProperties":true}""",
            """{"type":"array","items":{"type":"object","properties":{},"required":[],"additionalProperties":false}}""",
            """{"type":"array","items":{"type":"string"},"maxItems":65}""",
            """{"type":"array"}""",
        )) {
            assertThrows(schema, Exception::class.java) { ToolSchema.check(Json.parseToJsonElement(schema).jsonObject) }
        }
    }

    @Test
    fun `scalar arrays validate items and bounds for inputs`() {
        val schema =
            Json
                .parseToJsonElement(
                    """{"type":"object","additionalProperties":false,"required":[],"properties":{
                "tags":{"type":"array","items":{"type":"string","enum":["a","b"]},"minItems":1,"maxItems":2}}}""",
                ).jsonObject
        ToolSchema.check(schema)
        assertNull(ToolSchema.error(schema, Json.parseToJsonElement("""{"tags":["a","b"]}""")))
        assertNull(ToolSchema.error(schema, ToolSchema.coerce(schema, mapOf("tags" to """["b"]"""))))
        for (args in listOf("""{"tags":[]}""", """{"tags":["c"]}""", """{"tags":["a","b","a"]}""", """{"tags":"a"}""")) {
            assertNotNull(ToolSchema.error(schema, Json.parseToJsonElement(args)))
        }
        assertNotNull(ToolSchema.error(schema, ToolSchema.coerce(schema, mapOf("tags" to "not json"))))
    }

    @Test
    fun `output schemas may nest open objects inside arrays and still bound them`() {
        val schema =
            Json
                .parseToJsonElement(
                    """{"type":"object","additionalProperties":false,"required":["items"],"properties":{
                "items":{"type":"array","maxItems":2,"items":{"type":"object","additionalProperties":true,"required":["id"],"properties":{
                    "id":{"type":"string"}}}}}}""",
                ).jsonObject
        assertThrows(Exception::class.java) { ToolSchema.check(schema) }
        ToolSchema.check(schema, output = true)
        assertNull(ToolSchema.error(schema, Json.parseToJsonElement("""{"items":[{"id":"a","extra":{"deep":true}}]}""")))
        for (value in listOf(
            """{"items":[{"extra":1}]}""",
            """{"items":[{"id":"a"},{"id":"b"},{"id":"c"}]}""",
            """{"items":[{"id":1}]}""",
        )) {
            assertNotNull(ToolSchema.error(schema, Json.parseToJsonElement(value)))
        }
    }
}
