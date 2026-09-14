package com.colonelpanic.eva.data

import com.colonelpanic.eva.conversation.prompt.PromptConfig
import com.colonelpanic.eva.conversation.prompt.PromptDefaults
import com.colonelpanic.eva.conversation.prompt.PromptYaml
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class RepositoryPrompt(
    val source: String,
    val config: PromptConfig,
)

/** Loads a bounded prompt component catalog from a raw HTTPS file. */
class PromptRepository(
    private val fetch: (String, Int) -> ByteArray = PromptRepositoryHttpClient()::fetch,
) {
    fun load(source: String): RepositoryPrompt {
        val normalized = sourceUrl(source).toString()
        val bytes = fetch(normalized, MAX_BYTES)
        require(bytes.size <= MAX_BYTES) { "Instruction source is too large" }
        val config = PromptYaml.decode(bytes.toString(Charsets.UTF_8)).validated(PromptDefaults.VARIABLES)
        return RepositoryPrompt(normalized, config)
    }

    companion object {
        const val MAX_BYTES = 1_048_576
        const val DEFAULT_SOURCE =
            "https://raw.githubusercontent.com/colonelpanic8/eva-instructions/main/eva-prompt.yaml"

        fun sourceUrl(value: String): HttpUrl =
            value.trim().toHttpUrl().also {
                require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null) {
                    "Use an HTTPS instruction source URL without credentials or fragments"
                }
            }
    }
}

private class PromptRepositoryHttpClient {
    private val client =
        OkHttpClient
            .Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

    fun fetch(
        url: String,
        maxBytes: Int,
    ): ByteArray {
        val request = Request.Builder().url(PromptRepository.sourceUrl(url)).build()
        return client.newCall(request).execute().use { response ->
            require(response.code == 200) { "Instruction source returned HTTP ${response.code}; use a raw HTTPS file URL" }
            require(response.body.contentLength() <= maxBytes) { "Instruction source is too large" }
            response.body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= maxBytes) { "Instruction source is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }
}
