package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InteractionMode
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.capability.WaitBudget
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Read-only check of the Messaging bridge package against a real bridge. It runs only when
 * EVA_LIVE_BRIDGE_ORIGIN and EVA_LIVE_BRIDGE_TOKEN are set, never sends or starts a chat, and prints
 * counts and field presence rather than message content.
 */
class MessagingBridgeLiveTest {
    private val origin = System.getenv("EVA_LIVE_BRIDGE_ORIGIN")
    private val token = System.getenv("EVA_LIVE_BRIDGE_TOKEN")

    @Test
    fun `the package's lookups read a live bridge`() =
        runBlocking {
            assumeTrue(!origin.isNullOrBlank() && !token.isNullOrBlank())
            val json =
                generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
                    .map { File(it, "docs/examples/messaging-bridge.json") }
                    .first { it.isFile }
                    .readText()
            val definition = configurePackage(PackageCodec.decode(json), origin!!.trimEnd('/'))
            val http = PackageHttpClient({ approved, _ -> BearerCredential.create(approved, token!!) })
            val host =
                object : DeclarativeHost {
                    override suspend fun unavailableReason(binding: DeclarativeBinding): String? = null

                    override suspend fun launch(request: IntentRequest): ExecutionOutcome = error("unused")

                    override suspend fun query(
                        request: ContentRequest,
                        timeoutMillis: Long,
                    ): ContentRows = error("unused")

                    override suspend fun request(
                        request: HttpRequest,
                        timeoutMillis: Long,
                    ): HttpResponse = http.execute(request, timeoutMillis)
                }

            suspend fun read(
                tool: String,
                arguments: Map<String, String>,
            ): ExecutionOutcome {
                val capability = definition.capabilities.single { it.name == tool }
                val outcome =
                    DeclarativeBackend(capability, host) { WaitBudget(InteractionMode.TYPED, 20_000, null, null) }
                        .execute(ToolProposal("live-$tool", "live.$tool", arguments, "live check", "live"))
                assertEquals("$tool: ${outcome.message.take(300)}", InvocationStatus.COMPLETED, outcome.status)
                assertFalse(outcome.message.contains(token!!))
                return outcome
            }

            fun items(outcome: ExecutionOutcome) = checkNotNull(outcome.data).getValue("items").jsonArray.map { it.jsonObject }

            fun shape(
                label: String,
                rows: List<JsonObject>,
            ) = println(
                "$label: ${rows.size} items; fields present: " +
                    rows
                        .flatMap { row -> row.filterValues { it !is kotlinx.serialization.json.JsonNull }.keys }
                        .groupingBy { it }
                        .eachCount(),
            )

            val conversations = items(read("conversations", mapOf("limit" to "5")))
            shape("conversations", conversations)
            val id = (conversations.first().getValue("id") as JsonPrimitive).content
            shape("messages", items(read("messages", mapOf("conversation" to id, "limit" to "5"))))
            shape("contacts", items(read("contacts", mapOf("query" to "a", "limit" to "3"))))
        }
}
