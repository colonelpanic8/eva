package com.colonelpanic.eva.providers.openai

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiModelCatalogTest {
    private fun client(
        code: Int,
        body: String,
    ) = OkHttpClient
        .Builder()
        .addInterceptor { chain ->
            Response
                .Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("x")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

    @Test
    fun `speech models are kept out of the text list and vice versa`() {
        val kinds =
            OpenAiModelCatalog.classify(
                listOf(
                    "gpt-6-astra",
                    "gpt-5.6-luna",
                    "gpt-realtime-2.1",
                    "gpt-live-1",
                    "gpt-4o-audio-preview",
                    "gpt-4o-transcribe",
                    "tts-1",
                    "text-embedding-3-large",
                    "dall-e-3",
                    "omni-moderation-latest",
                    "o3-mini",
                ),
            )
        assertEquals(listOf("gpt-5.6-luna", "gpt-6-astra", "o3-mini"), kinds[ModelKind.TEXT])
        assertEquals(listOf("gpt-4o-audio-preview", "gpt-live-1", "gpt-realtime-2.1"), kinds[ModelKind.REALTIME])
    }

    @Test
    fun `the account's own model list is used`() =
        runTest {
            val catalog =
                OpenAiModelCatalog(
                    client(200, """{"data":[{"id":"gpt-6-astra"},{"id":"gpt-realtime-2.1"},{"id":"tts-1"}]}"""),
                    StandardTestDispatcher(testScheduler),
                )
            val kinds = catalog.load(ApiKeyAccess("sk-test", "https://example.test"))
            assertEquals(listOf("gpt-6-astra"), kinds[ModelKind.TEXT])
            assertEquals(listOf("gpt-realtime-2.1"), kinds[ModelKind.REALTIME])
        }

    @Test
    fun `a subscription account lists its models by slug`() {
        val body =
            kotlinx.serialization.json.Json
                .parseToJsonElement("""{"models":[{"slug":"gpt-6-astra"},{"slug":"gpt-5.5"}]}""")
                .jsonObject
        assertEquals(listOf("gpt-6-astra", "gpt-5.5"), OpenAiModelCatalog.identifiers(body))
    }

    @Test
    fun `a rejected list surfaces the provider message`() =
        runTest {
            val catalog =
                OpenAiModelCatalog(
                    client(401, """{"error":{"message":"Incorrect API key provided"}}"""),
                    StandardTestDispatcher(testScheduler),
                )
            val error = runCatching { catalog.load(ApiKeyAccess("sk-bad", "https://example.test")) }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertTrue(error!!.message!!.contains("Incorrect API key provided"))
        }
}
