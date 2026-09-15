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
    fun `Mova discovery defaults and handoffs match the provider and intent contracts`() {
        val capabilities = contentFixture("mova-content").capabilities.associateBy { it.name }
        val find = capabilities.getValue("find_todos")
        val request = BindingArguments(find, emptyMap()).content(find.binding as DeclarativeBinding.Content)
        assertEquals("content://com.colonelpanic.mova.provider/todos?limit=25", request.uri)
        assertEquals("integer", request.projection["pos"])
        val templates = capabilities.getValue("list_templates").binding as DeclarativeBinding.Content
        assertEquals(setOf("key", "name", "is_default", "title_prompt", "prompts_json", "capture_uri"), templates.projection.keys)
        assertEquals("boolean", templates.projection["is_default"])
        val capture = capabilities.getValue("capture_todo")
        assertEquals(
            "mova://capture?template=work%20%26%20home",
            BindingArguments(capture, mapOf("template" to "work & home")).intent(capture.binding as DeclarativeBinding.Intent).uri,
        )
        val open = capabilities.getValue("open_todo")
        assertEquals(
            "mova://open?id=org%201",
            BindingArguments(open, mapOf("id" to "org 1")).intent(open.binding as DeclarativeBinding.Intent).uri,
        )
        assertEquals(
            "mova://open?file=todo%2Finbox.org&pos=42&title=Heading",
            BindingArguments(open, mapOf("file" to "todo/inbox.org", "pos" to "42", "title" to "Heading"))
                .intent(open.binding)
                .uri,
        )
        val intentClasses =
            capabilities.values
                .mapNotNull { capability ->
                    (capability.binding as? DeclarativeBinding.Intent)?.let { capability.name to it.targetClass }
                }.toMap()
        assertEquals("com.colonelpanic.mova.intents.IntentActivity", intentClasses.getValue("create_todo"))
        assertEquals("com.colonelpanic.mova.QuickCaptureActivity", intentClasses.getValue("capture_todo"))
        assertEquals("com.colonelpanic.mova.VoiceQuickCaptureActivity", intentClasses.getValue("capture_todo_voice"))
        assertEquals("com.colonelpanic.mova.MainActivity", intentClasses.getValue("open_todo"))
        assertTrue(intentClasses.values.all { it != null })
    }

    @Test
    fun `Paseo catalog ids retain their host when passed to an intent`() {
        val capabilities = contentFixture().capabilities.associateBy { it.name }
        val agents = capabilities.getValue("list_agents")
        val request =
            BindingArguments(
                agents,
                mapOf("workspaceId" to "ws/1", "q" to "name & value"),
            ).content(agents.binding as DeclarativeBinding.Content)
        assertEquals("content://sh.paseo.assistant/agents?workspaceId=ws%2F1&q=name%20%26%20value", request.uri)
        assertTrue(setOf("id", "serverId", "workspaceId", "name").all { it in request.projection })
        val open = capabilities.getValue("open_agent")
        assertEquals(
            "paseo://agent?agentId=agent%2F1&serverId=host%201",
            BindingArguments(
                open,
                mapOf("agentId" to "agent/1", "serverId" to "host 1"),
            ).intent(open.binding as DeclarativeBinding.Intent).uri,
        )
    }

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
        val agenda = contentFixture("mova-content").capabilities.single { it.name == "read_agenda" }
        val query =
            BindingArguments(agenda, mapOf("date" to "2026-09-14", "span" to "week", "include_completed" to "false"))
                .content(agenda.binding as DeclarativeBinding.Content)
        assertEquals(
            "content://com.colonelpanic.mova.provider/agenda?" +
                "date=2026-09-14&span=week&include_completed=false",
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
        val capability = contentFixture("mova-content").capabilities.single { it.name == "read_todo" }
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
