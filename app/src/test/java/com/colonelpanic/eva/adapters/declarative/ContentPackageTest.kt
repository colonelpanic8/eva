package com.colonelpanic.eva.adapters.declarative

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

internal fun contentFixture(name: String = "paseo-content"): PackageDefinition =
    PackageCodec.decode(
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "docs/examples/$name.json") }
            .first { it.isFile }
            .readText(),
    )

class ContentPackageTest {
    @Test
    fun `both mixed fixtures decode and queries preserve types and omit absent optional slots`() {
        for (name in listOf("mova-content", "paseo-content")) {
            val definition = contentFixture(name)
            assertTrue(definition.capabilities.any { it.binding is DeclarativeBinding.Intent })
            assertTrue(definition.contentBindings().isNotEmpty())
            assertTrue(definition.capabilities.filter { it.binding is DeclarativeBinding.Content }.all { it.effect == PackageEffect.READ })
        }
        val capability = contentFixture().capabilities.first()
        val binding = capability.binding as DeclarativeBinding.Content
        assertEquals(binding.uri, BindingArguments(capability, emptyMap()).content(binding).uri)
        val request = BindingArguments(capability, mapOf("q" to "a b&x=/#😀", "limit" to "7")).content(binding)
        assertEquals("content://sh.paseo.assistant/workspaces?q=a%20b%26x%3D%2F%23%F0%9F%98%80&limit=7", request.uri)
        val agenda = contentFixture("mova-content").capabilities.single { it.name == "agenda" }
        val query =
            BindingArguments(agenda, mapOf("date" to "2026-09-14", "span" to "3", "include_completed" to "false"))
                .content(agenda.binding as DeclarativeBinding.Content)
        assertEquals(
            "content://com.colonelpanic.mova.provider/agenda?" +
                "date=2026-09-14&span=3&include_completed=false",
            query.uri,
        )
    }

    @Test
    fun `literal default required and fixed query names follow intent slot rules`() {
        val capability = contentFixture().capabilities.first()
        val binding = capability.binding as DeclarativeBinding.Content
        val mapped =
            binding.copy(
                query =
                    linkedMapOf(
                        "fixed & name" to ScalarSlot.Literal(JsonPrimitive(true), "boolean"),
                        "limit" to ScalarSlot.Argument("limit", "integer", JsonPrimitive(5)),
                        "q" to ScalarSlot.Argument("q", "string", required = true),
                    ),
            )
        assertThrows(Exception::class.java) { BindingArguments(capability, emptyMap()).content(mapped) }
        assertEquals(
            "${binding.uri}?fixed%20%26%20name=true&limit=5&q=term",
            BindingArguments(capability, mapOf("q" to "term")).content(mapped).uri,
        )
        val literal =
            jsonWithUri(
                JsonObject(
                    mapOf(
                        "base" to JsonPrimitive(binding.uri),
                        "query" to
                            JsonObject(
                                mapOf("fixed" to JsonObject(mapOf("value" to JsonPrimitive(false), "type" to JsonPrimitive("boolean")))),
                            ),
                    ),
                ),
            )
        assertEquals(
            false,
            (
                PackageCodec
                    .decode(literal)
                    .contentBindings()
                    .first()
                    .query
                    .getValue("fixed") as ScalarSlot.Literal
            ).value.content.toBoolean(),
        )
        assertNotEquals(
            capability.binding,
            PackageCodec
                .decode(literal)
                .capabilities
                .first()
                .binding,
        )
    }

    @Test
    fun `path slots are encoded segments and reject missing or structural values`() {
        val capability = contentFixture("mova-content").capabilities.single { it.name == "todo" }
        val binding = capability.binding as DeclarativeBinding.Content
        assertEquals(
            "content://com.colonelpanic.mova.provider/todos/id%20%3F%23%25",
            BindingArguments(capability, mapOf("id" to "id ?#%")).content(binding).uri,
        )
        for (id in listOf("", ".", "..", "a/b", "a\\b")) {
            assertThrows(Exception::class.java) { BindingArguments(capability, mapOf("id" to id)).content(binding) }
        }
        assertThrows(Exception::class.java) { BindingArguments(capability, emptyMap()).content(binding) }
    }

    @Test
    fun `content URI objects stay closed bounded scalar and within their fixed authority`() {
        val base = "content://sh.paseo.assistant/workspaces"

        fun obj(vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(mapOf(*fields))
        val stringSlot = obj("argument" to JsonPrimitive("q"), "type" to JsonPrimitive("string"))
        val invalid =
            listOf(
                obj("base" to JsonPrimitive(base), "opaque" to stringSlot),
                obj("base" to JsonPrimitive("content://other.provider/workspaces")),
                obj("base" to JsonPrimitive("content://user@sh.paseo.assistant/workspaces")),
                obj("base" to JsonPrimitive("content://sh.paseo.assistant:80/workspaces")),
                obj("base" to JsonPrimitive("https://sh.paseo.assistant/workspaces")),
                obj("base" to JsonPrimitive("$base?q=fixed")),
                obj("base" to JsonPrimitive("$base#fragment")),
                obj("base" to JsonPrimitive("$base/{q}")),
                obj("base" to JsonPrimitive("$base/{bad")),
                obj("base" to JsonPrimitive("$base/../agents")),
                obj("base" to JsonPrimitive(base), "path" to obj("q" to stringSlot)),
                obj(
                    "base" to JsonPrimitive(base),
                    "query" to obj("q" to obj("argument" to JsonPrimitive("q"), "type" to JsonPrimitive("integer"))),
                ),
                obj(
                    "base" to JsonPrimitive(base),
                    "query" to obj("q" to obj("argument" to JsonPrimitive("q"), "type" to JsonPrimitive("array"))),
                ),
                obj(
                    "base" to JsonPrimitive(base),
                    "query" to obj("q" to obj("value" to JsonPrimitive("false"), "type" to JsonPrimitive("boolean"))),
                ),
                obj(
                    "base" to JsonPrimitive(base),
                    "query" to
                        obj(
                            "q" to obj("argument" to JsonPrimitive("q"), "type" to JsonPrimitive("string"), "extra" to JsonPrimitive(true)),
                        ),
                ),
                obj("base" to JsonPrimitive(base), "query" to obj("" to stringSlot)),
                obj("base" to JsonPrimitive(base), "query" to obj("x\n" to stringSlot)),
                obj("base" to JsonPrimitive(base), "query" to obj("x".repeat(201) to stringSlot)),
                obj("base" to JsonPrimitive(base), "query" to JsonObject((0..64).associate { "q$it" to stringSlot })),
            )
        invalid.forEach { uri -> assertThrows(uri.toString(), Exception::class.java) { PackageCodec.decode(jsonWithUri(uri)) } }
        val capability = contentFixture().capabilities.first()
        val oversized =
            (capability.binding as DeclarativeBinding.Content).copy(
                query =
                    (0..63).associate {
                        "q$it" to
                            ScalarSlot.Argument("q", "string")
                    },
            )
        assertThrows(Exception::class.java) { BindingArguments(capability, mapOf("q" to "😀".repeat(100))).content(oversized) }
    }

    private fun jsonWithUri(uri: JsonObject): String {
        val document = contentFixture().document
        val capability = (document.getValue("capabilities") as JsonArray).first().jsonObject
        val binding = capability.getValue("binding").jsonObject
        return JsonObject(
            document + ("capabilities" to JsonArray(listOf(JsonObject(capability + ("binding" to JsonObject(binding + ("uri" to uri))))))),
        ).toString()
    }
}
