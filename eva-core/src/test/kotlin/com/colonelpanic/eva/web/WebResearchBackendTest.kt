package com.colonelpanic.eva.web

import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.conversation.prompt.Wording
import com.colonelpanic.eva.providers.openai.ApiKeyAccess
import com.colonelpanic.eva.providers.openai.ChatGptTokenSource
import com.colonelpanic.eva.providers.openai.ChatGptTokens
import com.colonelpanic.eva.providers.openai.SubscriptionAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebResearchBackendTest {
    private val instant = Instant.parse("2026-09-30T12:00:00Z")
    private val apiKey = ApiKeyAccess("test-key")
    private val subscription =
        SubscriptionAccess(
            ChatGptTokenSource {
                ChatGptTokens("id", "test-token", "refresh", "account", null, null, Long.MAX_VALUE)
            },
            "1.0.0",
        )

    @Test fun isolatedRequestUsesOnlyHostedSearchForBothAccessModes() =
        runBlocking {
            for (access in listOf(apiKey, subscription)) {
                var sent: JsonObject? = null
                val client =
                    client { request ->
                        val buffer = Buffer()
                        request.body!!.writeTo(buffer)
                        sent = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                        assertEquals(if (access === apiKey) "Bearer test-key" else "Bearer test-token", request.header("Authorization"))
                        if (access === subscription) {
                            assertEquals("eva", request.header("originator"))
                            assertEquals("account", request.header("chatgpt-account-id"))
                        }
                        response(request, if (access === subscription) fixture() else completedJson())
                    }
                val backend = WebResearchBackend({ access }, client = client, now = { instant })
                val record =
                    dispatch(
                        backend,
                        mapOf(
                            "question" to "When does it arrive?",
                            "sourceUrl" to "https://science.nasa.gov/mission/europa-clipper/",
                        ),
                    )
                assertEquals(InvocationStatus.COMPLETED, record.status)
                assertEquals(if (access === apiKey) "api_key" else "subscription", record.data!!["accessMode"]!!.jsonPrimitive.content)
                val body = sent!!
                assertEquals("gpt-6-sol", body["model"]!!.jsonPrimitive.content)
                assertEquals("low", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
                assertEquals("false", body["store"]!!.jsonPrimitive.content)
                assertEquals((access === subscription).toString(), body["stream"]!!.jsonPrimitive.content)
                assertEquals(Wording.bundled.message("web-research-instructions"), body["instructions"]!!.jsonPrimitive.content)
                assertEquals(Json.parseToJsonElement("""[{"type":"web_search","external_web_access":true}]"""), body["tools"])
                assertEquals(Json.parseToJsonElement("""["web_search_call.action.sources"]"""), body["include"])
                assertEquals(1, body["input"]!!.jsonArray.size)
                assertEquals(
                    "user",
                    body["input"]!!
                        .jsonArray
                        .single()
                        .jsonObject["role"]!!
                        .jsonPrimitive.content,
                )
                assertTrue(
                    body["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content.endsWith(
                        "https://science.nasa.gov/mission/europa-clipper/",
                    ),
                )
                assertFalse(body.containsKey("previous_response_id"))
                assertFalse(body.containsKey("conversation"))
            }
        }

    @Test fun subscriptionStreamYieldsAnswerDeduplicatedSourcesActionsAndJournaledExternalProvenance() =
        runBlocking {
            val backend = WebResearchBackend({ subscription }, client = client { response(it, fixture()) }, now = { instant })
            val record = dispatch(backend)
            assertEquals(InvocationStatus.COMPLETED, record.status)
            assertTrue(record.message.startsWith("NASA plans arrival at Jupiter in April 2030; the schedule could change."))
            assertTrue(record.message.contains("Europa Clipper: https://science.nasa.gov/mission/europa-clipper/"))
            val data = record.data!!
            assertFalse(data.containsKey("answer"))
            assertEquals("message", data["answerLocation"]!!.jsonPrimitive.content)
            assertEquals(
                listOf("Europa Clipper", "Europa Clipper at JPL"),
                data["sources"]!!.jsonArray.map {
                    it.jsonObject["title"]!!.jsonPrimitive.content
                },
            )
            assertEquals(
                listOf("search", "open_page", "find_in_page"),
                data["searches"]!!.jsonArray.map {
                    it.jsonObject["type"]!!.jsonPrimitive.content
                },
            )
            assertEquals(
                "NASA Europa Clipper arrival Jupiter April 2030",
                data["searches"]!!
                    .jsonArray[0]
                    .jsonObject["queries"]!!
                    .jsonArray
                    .single()
                    .jsonPrimitive.content,
            )
            assertEquals(
                "https://science.nasa.gov/mission/europa-clipper/",
                data["searches"]!!
                    .jsonArray[1]
                    .jsonObject["url"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(instant.toString(), data["retrievedAt"]!!.jsonPrimitive.content)
            assertEquals(WebResearchBackend.ID, record.provenance!!.source.id)
        }

    @Test fun optionalSourcesCanBeAbsentAndMissingAnnotationsAreHonest() =
        runBlocking {
            val withoutSources =
                fixture().replace(
                    ",\"sources\":[{\"type\":\"url\",\"url\":\"https://science.nasa.gov/mission/europa-clipper/\",\"title\":\"Europa Clipper\"}]",
                    "",
                )
            val cited = dispatch(WebResearchBackend({ subscription }, client = client { response(it, withoutSources) }))
            assertEquals(2, cited.data!!["sources"]!!.jsonArray.size)
            val uncited = dispatch(WebResearchBackend({ apiKey }, client = client { response(it, completedJson()) }))
            assertEquals(InvocationStatus.COMPLETED, uncited.status)
            assertTrue(uncited.message.contains("No web sources were retrieved"))
            assertTrue(uncited.data!!["sources"]!!.jsonArray.isEmpty())
            val searched =
                completedJson().replace(
                    "\"output\":[",
                    "\"output\":[{\"type\":\"web_search_call\",\"action\":{\"type\":\"search\",\"query\":\"test\"}},",
                )
            val noCitations = dispatch(WebResearchBackend({ apiKey }, client = client { response(it, searched) }))
            assertTrue(noCitations.message.contains("no source citations"))
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun totalDeadlineIncludesAuthorizationAndUsesPortableTimeout() =
        kotlinx.coroutines.test.runTest {
            val slowAccess =
                SubscriptionAccess(
                    ChatGptTokenSource {
                        kotlinx.coroutines.delay(10000)
                        ChatGptTokens("id", "token", "refresh", null, null, null, Long.MAX_VALUE)
                    },
                    "1.0.0",
                )
            val backend =
                WebResearchBackend({
                    slowAccess
                }, { WebResearchConfiguration(timeoutSeconds = 5) }, client = client { error("Must not submit after deadline") })
            val record = dispatch(backend)
            assertEquals(InvocationStatus.FAILED, record.status)
            assertTrue(record.message.contains("timed out"))
            assertEquals(5000L, testScheduler.currentTime)
        }

    @Test fun missingAccessAndDisablementAreNotExecutedAndMakeNoRequest() =
        runBlocking {
            val noNetwork = client { error("Must not send a request") }
            val missing = dispatch(WebResearchBackend({ null }, client = noNetwork))
            assertEquals(InvocationStatus.NOT_EXECUTED, missing.status)
            assertTrue(missing.message.contains("Sign in to ChatGPT"))
            val disabled = dispatch(WebResearchBackend({ apiKey }, { WebResearchConfiguration(enabled = false) }, client = noNetwork))
            assertEquals(InvocationStatus.NOT_EXECUTED, disabled.status)
        }

    @Test fun knownErrorsAndDroppedStreamsFailWithoutLeakingProviderBodyOrRetrying() =
        runBlocking {
            var requests = 0
            val rejected =
                dispatch(
                    WebResearchBackend(
                        { subscription },
                        client =
                            client {
                                requests++
                                response(it, """{"error":{"message":"secret echoed from upstream"}}""", 401)
                            },
                    ),
                )
            assertEquals(InvocationStatus.FAILED, rejected.status)
            assertTrue(rejected.message.contains("HTTP 401"))
            assertFalse(rejected.message.contains("secret"))
            assertEquals(1, requests)
            val partial = fixture().substringBefore("event: response.completed")
            val dropped = dispatch(WebResearchBackend({ subscription }, client = client { response(it, partial) }))
            assertEquals(InvocationStatus.FAILED, dropped.status)
            val timeout = dispatch(WebResearchBackend({ apiKey }, client = client { throw java.net.SocketTimeoutException("private") }))
            assertEquals(InvocationStatus.FAILED, timeout.status)
            assertTrue(timeout.message.contains("timed out"))
        }

    @Test fun cancellationCancelsHttpCallAndKeepsDispatcherCancellationDistinct() =
        runBlocking {
            val started = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        started.countDown()
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
                        if (chain.call().isCanceled()) cancelled.countDown()
                        throw java.io.IOException("cancelled")
                    }.build()
            val repository = MemoryInvocationRepository()
            val backend = WebResearchBackend({ apiKey }, client = client)
            val registry = CapabilityRegistry(mapOf(WebResearchBackend.ID to backend), listOf(WebResearchBackend.definition))
            val dispatcher = CapabilityDispatcher(registry, repository)
            val job = launch(Dispatchers.Default) { dispatcher.execute(proposal(registry)) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertEquals(InvocationStatus.UNKNOWN, repository.history().single().status)
        }

    @Test fun invalidSourceUrlsAreRejectedBeforeNetworkAndDataFitsResultBudget() =
        runBlocking {
            for (url in listOf("http://example.com", "https://user:password@example.com", "https:///missing-host")) {
                val record =
                    dispatch(
                        WebResearchBackend({ apiKey }, client = client { error("Must not submit") }),
                        mapOf(
                            "question" to "read",
                            "sourceUrl" to url,
                        ),
                    )
                assertEquals(InvocationStatus.NOT_EXECUTED, record.status)
            }
            val message =
                buildJsonObject {
                    put("type", "message")
                    put(
                        "content",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("type", "output_text")
                                    put("text", "\u0001".repeat(20000))
                                },
                            ),
                        ),
                    )
                }
            val outcome =
                WebResearchBackend.parse(
                    buildJsonObject {
                        put("output", JsonArray(listOf(message)))
                    },
                    Wording.bundled,
                    instant,
                    "subscription",
                )
            assertTrue(outcome.data!!.toString().length <= 16384)
            assertTrue(outcome.message.length <= 16384)
            assertTrue(outcome.data.toString().length + JsonPrimitive(outcome.message).toString().length <= 16384)
            assertFalse(outcome.data.containsKey("answer"))
            assertEquals(JsonPrimitive(true), outcome.data["truncated"])
        }

    @Test fun completedEventCanSupplyAllOutputWithoutItemEvents() =
        runBlocking {
            val result = Json.parseToJsonElement(completedJson()).jsonObject
            val stream =
                "data: " +
                    buildJsonObject {
                        put("type", "response.completed")
                        put("response", result)
                    } + "\n\n"
            val record = dispatch(WebResearchBackend({ subscription }, client = client { response(it, stream) }))
            assertEquals(InvocationStatus.COMPLETED, record.status)
            assertTrue(record.message.startsWith("Unverified answer."))
            assertFalse(record.data!!.containsKey("answer"))
        }

    @Test fun longAnswerAppearsOnceAndSourcesStayWithinCombinedBudget() {
        val answer = "Distinct research answer. ".repeat(250)
        val annotations =
            JsonArray(
                (1..100).map {
                    buildJsonObject {
                        put("type", "url_citation")
                        put("url", "https://example.com/" + "path".repeat(50) + "/$it")
                        put("title", "Source $it")
                    }
                },
            )
        val response =
            buildJsonObject {
                put(
                    "output",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("type", "message")
                                put(
                                    "content",
                                    JsonArray(
                                        listOf(
                                            buildJsonObject {
                                                put("type", "output_text")
                                                put("text", answer)
                                                put("annotations", annotations)
                                            },
                                        ),
                                    ),
                                )
                            },
                        ),
                    ),
                )
            }
        val outcome = WebResearchBackend.parse(response, Wording.bundled, instant, "api_key")
        assertTrue(outcome.message.startsWith(answer.trim()))
        assertFalse(outcome.data!!.toString().contains("Distinct research answer"))
        assertTrue(outcome.data["sources"]!!.jsonArray.isNotEmpty())
        val kept = outcome.data["sources"]!!.jsonArray.size
        assertTrue(kept < 100)
        assertEquals(JsonPrimitive(true), outcome.data["truncated"])
        assertTrue(outcome.message, outcome.message.endsWith("Further sources omitted to fit the result budget: ${100 - kept}."))
        assertTrue(JsonPrimitive(outcome.message).toString().length + outcome.data.toString().length <= 16384)
    }

    private suspend fun dispatch(
        backend: WebResearchBackend,
        args: Map<String, String> =
            mapOf("question" to "When does Europa Clipper arrive?"),
    ) = CapabilityRegistry(mapOf(WebResearchBackend.ID to backend), listOf(WebResearchBackend.definition)).let { registry ->
        val repository = MemoryInvocationRepository()
        val record = CapabilityDispatcher(registry, repository).execute(proposal(registry).copy(arguments = args))
        assertEquals(record, repository.history().single())
        record
    }

    private fun proposal(registry: CapabilityRegistry) =
        ToolProposal("research", WebResearchBackend.ID, mapOf("question" to "Research"), "Research", registry.snapshot.revision)

    private fun fixture() = javaClass.getResourceAsStream("/web/research.sse")!!.bufferedReader().use { it.readText() }

    private fun completedJson() =
        """{"id":"response","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Unverified answer."}]}]}"""

    private fun client(reply: (okhttp3.Request) -> Response) =
        OkHttpClient
            .Builder()
            .retryOnConnectionFailure(false)
            .addInterceptor {
                reply(it.request())
            }.build()

    private fun response(
        request: okhttp3.Request,
        body: String,
        code: Int = 200,
    ) = Response
        .Builder()
        .request(
            request,
        ).protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Test")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()
}
