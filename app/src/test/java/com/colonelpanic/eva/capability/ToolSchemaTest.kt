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
    fun `unsupported arrays nullable and open objects fail at catalog creation`() {
        for (schema in listOf(
            """{"type":"array","items":{"type":"string"}}""",
            """{"type":["string","null"]}""",
            """{"type":"object","properties":{},"required":[],"additionalProperties":true}""",
        )) {
            assertThrows(Exception::class.java) { ToolSchema.check(Json.parseToJsonElement(schema).jsonObject) }
        }
    }
}
