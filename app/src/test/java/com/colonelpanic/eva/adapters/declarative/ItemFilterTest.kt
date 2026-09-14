package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.InteractionMode
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.capability.WaitBudget
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemFilterTest {
    private val projection =
        ItemProjection(
            listOf("/items"),
            "{title}",
            mapOf("title" to ItemField("/title", "string", true)),
            1,
            "Narrow the search.",
            "/total",
            ItemFilter(listOf("/title", "/category"), "q"),
        )

    private fun render(
        json: String,
        query: String? = "work",
        items: Int = 1,
    ) = ItemResults.render(
        Json.parseToJsonElement(json),
        projection.copy(maxItems = items),
        1000,
        query?.let { mapOf("q" to it) }.orEmpty(),
    )

    @Test
    fun `filter scans past nonmatches before capping and uses OR case insensitive string matches`() {
        val json = """{"items":[{"title":"Other"},{"title":"First","category":"WoRk"},{"title":"Second WORK"}],"total":3}"""
        val limited = render(json)
        assertTrue(limited.contains("First"))
        assertFalse(limited.contains("Other"))
        assertFalse(limited.contains("Second"))
        assertTrue(limited.contains("[Truncated]"))
        val full = render(json, items = 2)
        assertTrue(full.contains("Second WORK"))
        assertFalse(full.contains("[Truncated]"))
        assertTrue(render(json, query = null).contains("Other"))
    }

    @Test
    fun `source truncation is not confused with filtered count or empty matches`() {
        val all = """{"items":[{"title":"WORK"},{"title":"Other"},{"title":"Third"}],"total":3}"""
        assertFalse(render(all).contains("[Truncated]"))
        val partial = render(all.replace("\"total\":3", "\"total\":10"), query = "absent")
        assertTrue(partial.contains("No matching items in the returned data"))
        assertTrue(partial.contains("additional matches may exist"))
        assertTrue(partial.contains("[Truncated]"))
        val nonstrings = render("""{"items":[{"title":"Other","category":["work"]},{"title":"Second","category":null}]}""")
        assertTrue(nonstrings.contains("No matching items"))
        assertFalse(nonstrings.contains("[Truncated]"))
    }

    private fun packageWithFilter(argument: String = "title"): String {
        val root = Json.parseToJsonElement(packageJson(httpBinding, "synchronous", false)).jsonObject
        val cap =
            root
                .getValue("capabilities")
                .jsonArray
                .single()
                .jsonObject
        val binding = cap.getValue("binding").jsonObject
        val result =
            Json.parseToJsonElement(
                """{"maxBytes":1000,"items":{
          "arrayPaths":["/items"],"line":"{title}","fields":{"title":{"pointer":"/title","type":"string"}},
          "maxItems":1,"truncationNote":"Narrow the search.","filter":{"fields":["/title"],"argument":"$argument"}}}""",
            )
        return JsonObject(
            root + (
                "capabilities" to
                    kotlinx.serialization.json.JsonArray(
                        listOf(
                            JsonObject(cap + ("binding" to JsonObject(binding + ("result" to result)))),
                        ),
                    )
            ),
        ).toString()
    }

    @Test
    fun `codec rejects unknown inputs filter fields and unbounded pointer lists`() {
        PackageCodec.decode(packageWithFilter())
        assertThrows(IllegalArgumentException::class.java) { PackageCodec.decode(packageWithFilter("missing")) }
        assertThrows(IllegalArgumentException::class.java) {
            PackageCodec.decode(packageWithFilter().replace("\"fields\":[\"/title\"]", "\"fields\":[]"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PackageCodec.decode(packageWithFilter().replace("\"argument\":\"title\"", "\"script\":\"x\",\"argument\":\"title\""))
        }
    }

    @Test
    fun `execution supplies invocation arguments to the local projection`() =
        runTest {
            val capability = PackageCodec.decode(packageWithFilter()).capabilities.single()
            val host =
                object : DeclarativeHost {
                    override suspend fun unavailableReason(binding: DeclarativeBinding): String? = null

                    override suspend fun launch(request: IntentRequest) = error("unused")

                    override suspend fun query(
                        request: ContentRequest,
                        timeoutMillis: Long,
                    ) = error("unused")

                    override suspend fun request(
                        request: HttpRequest,
                        timeoutMillis: Long,
                    ) = HttpResponse(200, """{"items":[{"title":"Other"},{"title":"Selected"}]}""")
                }
            val backend = DeclarativeBackend(capability, host) { WaitBudget(InteractionMode.TYPED, 30_000, null, null) }
            val result = backend.execute(ToolProposal("call", "example", mapOf("title" to "selected"), "search", "revision"))
            assertTrue(result.message.contains("Selected"))
            assertFalse(result.message.contains("Other"))
        }
}
