package com.colonelpanic.eva.web

import com.colonelpanic.eva.capability.CapabilitySource
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.tool
import com.colonelpanic.eva.conversation.prompt.Wording
import com.colonelpanic.eva.providers.MODEL_RESULT_CHARS
import com.colonelpanic.eva.providers.boundedResultText
import com.colonelpanic.eva.providers.openai.ApiKeyAccess
import com.colonelpanic.eva.providers.openai.OpenAiAccess
import com.colonelpanic.eva.providers.openai.ResponsesHttpException
import com.colonelpanic.eva.providers.openai.responsesPost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit

class WebResearchBackend(
    private val access: () -> OpenAiAccess?,
    private val configuration: () -> WebResearchConfiguration = { WebResearchConfiguration() },
    private val wording: () -> Wording = { Wording.bundled },
    private val client: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
    private val now: () -> Instant = Instant::now,
) : ExecutionBackend {
    override fun receiptSource() = CapabilitySource(ID, "Web research")

    override fun dispatchRejection(): String? = if (!configuration().enabled) wording().message("web-research-disabled") else null

    override suspend fun unavailableReason(): String? =
        dispatchRejection() ?: if (access() == null) wording().message("web-research-setup") else null

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
        val text = wording()
        val options = configuration()
        if (!options.enabled) return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, text.message("web-research-disabled"))
        val selected = access() ?: return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, text.message("web-research-setup"))
        return try {
            val result =
                withTimeoutOrNull(options.timeoutSeconds * 1000L) {
                    val boundedClient =
                        client
                            .newBuilder()
                            .retryOnConnectionFailure(false)
                            .followRedirects(false)
                            .callTimeout(options.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                            .readTimeout(options.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                            .build()
                    responsesPost(boundedClient, selected, request(arguments, options, text, !selected.serverKeepsHistory))
                } ?: return ExecutionOutcome(InvocationStatus.FAILED, text.message("web-research-timeout"))
            check((result["status"] as? JsonPrimitive)?.content == "completed")
            parse(result, text, now(), if (selected is ApiKeyAccess) "api_key" else "subscription")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: java.io.InterruptedIOException) {
            ExecutionOutcome(InvocationStatus.FAILED, text.message("web-research-timeout"))
        } catch (error: ResponsesHttpException) {
            ExecutionOutcome(
                InvocationStatus.FAILED,
                text.message("web-research-http-error").replace("{status}", error.statusCode.toString()),
            )
        } catch (_: Exception) {
            // Provider errors can echo request text or credentials; do not return them.
            ExecutionOutcome(InvocationStatus.FAILED, text.message("web-research-failed"))
        }
    }

    companion object {
        const val ID = "eva.web.research"
        val definition =
            tool(
                ID,
                "Research the web",
                Json
                    .parseToJsonElement(
                        """{"type":"object","properties":{"question":{"type":"string","minLength":1,"maxLength":1000},"sourceUrl":{"type":"string","minLength":1,"maxLength":2048}},"required":["question"],"additionalProperties":false}""",
                    ).jsonObject,
                readOnly = true,
                validateOperation = { args ->
                    when {
                        args.getValue("question").isBlank() -> Wording.bundled.message("web-research-question-invalid")

                        args["sourceUrl"]?.let {
                            !validUrl(
                                it,
                                httpsOnly = true,
                            )
                        } == true -> Wording.bundled.message("web-research-url-invalid")

                        else -> null
                    }
                },
            )

        internal fun request(
            arguments: Map<String, String>,
            options: WebResearchConfiguration,
            text: Wording,
            stream: Boolean,
        ) = buildJsonObject {
            put("model", options.model)
            put("instructions", text.message("web-research-instructions"))
            put("reasoning", buildJsonObject { put("effort", options.effort) })
            put("store", false)
            put("stream", stream)
            put(
                "tools",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "web_search")
                            put("external_web_access", true)
                        },
                    ),
                ),
            )
            put("include", JsonArray(listOf(JsonPrimitive("web_search_call.action.sources"))))
            put(
                "input",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "user")
                            put("content", arguments.getValue("question") + arguments["sourceUrl"]?.let { "\n$it" }.orEmpty())
                        },
                    ),
                ),
            )
        }

        internal fun parse(
            response: JsonObject,
            text: Wording,
            retrievedAt: Instant,
            accessMode: String,
        ): ExecutionOutcome {
            val sources = linkedMapOf<String, JsonObject>()
            val citedUrls = linkedSetOf<String>()
            val searches = mutableListOf<JsonObject>()
            val answers = mutableListOf<String>()
            var searched = false

            fun source(
                value: JsonObject,
                cited: Boolean = false,
            ) {
                val url = value.string("url") ?: return
                if (!validUrl(url)) return
                if (cited) citedUrls += url
                val existing = sources[url]
                if (existing != null && (!cited || existing.string("title") != url)) return
                sources[url] =
                    buildJsonObject {
                        put("title", value.string("title")?.take(240)?.trimEndSurrogate() ?: url)
                        put("url", url)
                    }
            }
            response.array("output").forEach { item ->
                when (item.string("type")) {
                    "message" -> {
                        item.array("content").filter { it.string("type") == "output_text" }.forEach { content ->
                            content.string("text")?.let(answers::add)
                            content.array("annotations").filter { it.string("type") == "url_citation" }.forEach { source(it, cited = true) }
                        }
                    }

                    "web_search_call" -> {
                        searched = true
                        val action = item["action"] as? JsonObject ?: return@forEach
                        action.array("sources").forEach { source(it) }
                        searches +=
                            buildJsonObject {
                                action.string("type")?.let { put("type", it) }
                                val queries =
                                    (action["queries"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                                        ?: listOfNotNull(action.string("query"))
                                if (queries.isNotEmpty()) put("queries", JsonArray(queries.take(10).map { JsonPrimitive(it.take(1000)) }))
                                action.string("url")?.takeIf { validUrl(it) }?.let { put("url", it) }
                            }
                    }
                }
            }
            val rawAnswer = answers.joinToString("\n").trim().ifBlank { text.message("web-research-no-answer") }
            var truncated = rawAnswer.length > 8000
            var excerpt = rawAnswer.take(8000).trimEndSurrogate()
            while (JsonPrimitive(excerpt).toString().length > 10000) {
                excerpt = excerpt.take(excerpt.length * 3 / 4).trimEndSurrogate()
                truncated = true
            }
            val answer = if (truncated) excerpt + "\n" + text.message("web-research-truncated") else rawAnswer
            val keptSources = mutableListOf<JsonObject>()
            val keptSearches = mutableListOf<JsonObject>()

            val note =
                when {
                    sources.isEmpty() && !searched -> text.message("web-research-no-sources")
                    sources.isEmpty() -> text.message("web-research-no-citations")
                    else -> ""
                }

            fun message() =
                boundedResultText(
                    listOf(
                        answer,
                        note,
                        keptSources
                            .takeIf { it.isNotEmpty() }
                            ?.let {
                                text.message("web-research-sources") + "\n" +
                                    it.joinToString("\n") { source -> "${source.string("title")}: ${source.string("url")}" }
                            }.orEmpty(),
                    ).filter { it.isNotBlank() }.joinToString("\n"),
                )

            fun data() =
                buildJsonObject {
                    put("answerLocation", "message")
                    put("accessMode", accessMode)
                    put("sources", JsonArray(keptSources))
                    put("searches", JsonArray(keptSearches))
                    put("retrievedAt", retrievedAt.toString())
                    put("truncated", truncated)
                }
            (citedUrls.mapNotNull(sources::get) + sources.filterKeys { it !in citedUrls }.values).forEach { source ->
                keptSources += source
                if (data().toString().length + JsonPrimitive(message()).toString().length > MODEL_RESULT_CHARS - 1024) {
                    keptSources.removeAt(keptSources.lastIndex)
                    truncated = true
                }
            }
            searches.forEach { search ->
                keptSearches += search
                if (data().toString().length + JsonPrimitive(message()).toString().length > MODEL_RESULT_CHARS - 1024) {
                    keptSearches.removeAt(keptSearches.lastIndex)
                    truncated = true
                }
            }
            return ExecutionOutcome(
                InvocationStatus.COMPLETED,
                message(),
                data(),
            )
        }

        private fun validUrl(
            url: String,
            httpsOnly: Boolean = false,
        ): Boolean =
            runCatching {
                val uri = URI(url)
                url.length <= 2048 && uri.scheme in (if (httpsOnly) setOf("https") else setOf("http", "https")) &&
                    !uri.host.isNullOrBlank() && uri.rawUserInfo == null
            }.getOrDefault(false)

        private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.content

        private fun JsonObject.array(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

        private fun String.trimEndSurrogate() = if (lastOrNull()?.isHighSurrogate() == true) dropLast(1) else this
    }
}
