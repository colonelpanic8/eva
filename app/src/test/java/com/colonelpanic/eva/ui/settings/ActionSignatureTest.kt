package com.colonelpanic.eva.ui.settings

import com.colonelpanic.eva.capability.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSignatureTest {
    private fun schema(
        text: String,
        output: Boolean = false,
    ): JsonObject = Json.parseToJsonElement(text).jsonObject.also { ToolSchema.check(it, output = output) }

    @Test
    fun inputPropertiesKeepDeclaredOrderWithTypesAndBounds() {
        val fields =
            schemaFields(
                schema(
                    """
                    {"type":"object","additionalProperties":false,"required":["action"],"properties":{
                      "action":{"type":"string","enum":["play","pause"],"description":"What to do"},
                      "limit":{"type":"integer","minimum":1,"maximum":20},
                      "tags":{"type":"array","items":{"type":"string","maxLength":40},"maxItems":5},
                      "headers":{"type":"object","additionalProperties":{"type":"string","maxLength":200},"maxProperties":8}
                    }}
                    """,
                ),
            )
        assertEquals(listOf("action", "limit", "tags", "headers"), fields.map { it.name })
        assertEquals(listOf("string", "integer", "string[]", "map<string>"), fields.map { it.type })
        assertEquals(listOf(true, false, false, false), fields.map { it.required })
        assertEquals(listOf("one of \"play\", \"pause\""), fields[0].constraints)
        assertEquals("What to do", fields[0].description)
        assertEquals(listOf("1–20"), fields[1].constraints)
        assertEquals(listOf("up to 5 items", "each item up to 40 characters"), fields[2].constraints)
        assertEquals(listOf("up to 8 keys", "each value up to 200 characters"), fields[3].constraints)
    }

    @Test
    fun outputObjectsInsideArraysAndMapsKeepTheirFields() {
        fun record(property: String) =
            """{"type":"object","additionalProperties":false,"required":["$property"],"properties":{"$property":{"type":"integer","minimum":0}}}"""
        val fields =
            schemaFields(
                schema(
                    """
                    {"type":"object","additionalProperties":false,"required":[],"properties":{
                      "visits":{"type":"array","items":${record("id")}},
                      "page":{"type":"object","additionalProperties":true,"required":[],"properties":{"next":{"type":"integer"}}},
                      "groups":{"type":"array","items":{"type":"array","items":${record("size")}}},
                      "byDay":{"type":"object","additionalProperties":${record("count")},"maxProperties":7}
                    }}
                    """,
                    output = true,
                ),
            )
        assertEquals(listOf("object[]", "object", "object[][]", "map<object>"), fields.map { it.type })
        assertEquals(listOf("id", "next", "size", "count"), fields.map { it.children.single().name })
        assertTrue(fields[0].children.single().required)
        assertEquals(listOf("at least 0"), fields[3].children.single().constraints)
        assertEquals(listOf("may hold other fields"), fields[1].constraints)
        assertEquals(listOf("up to 7 keys"), fields[3].constraints)
        assertTrue(schemaFields(null).isEmpty())
    }

    @Test
    fun anOpenOutputRootSaysSoWithOrWithoutDeclaredFields() {
        val bare = schema("""{"type":"object","additionalProperties":true,"required":[],"properties":{}}""", output = true)
        assertTrue(schemaFields(bare).isEmpty())
        assertEquals(listOf("may hold other fields"), schemaNotes(bare))
        val declared =
            schema(
                """{"type":"object","additionalProperties":true,"required":["ok"],"properties":{"ok":{"type":"boolean"}}}""",
                output = true,
            )
        assertEquals(listOf("ok"), schemaFields(declared).map { it.name })
        assertEquals(listOf("may hold other fields"), schemaNotes(declared))
    }
}
