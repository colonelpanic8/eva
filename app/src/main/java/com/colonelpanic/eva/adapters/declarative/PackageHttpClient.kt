package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.BoundedJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BindingNotSubmitted(
    message: String,
) : IllegalStateException(message)

class PackageHttpClient(
    private val credential: (String, String) -> HttpCredential?,
    client: OkHttpClient = OkHttpClient(),
) {
    private val client =
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .addNetworkInterceptor { chain ->
                val submitted = checkNotNull(chain.request().tag(AtomicBoolean::class.java))
                if (!submitted.compareAndSet(false, true)) throw IOException("Automatic follow-up refused")
                chain.proceed(chain.request())
            }.build()

    suspend fun execute(
        request: HttpRequest,
        timeoutMillis: Long,
    ): HttpResponse =
        withContext(Dispatchers.IO) {
            val origin = request.origin.toHttpUrl()
            val url = request.url.toHttpUrl()
            if (!url.isHttps || url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port ||
                url.username.isNotEmpty() || url.password.isNotEmpty()
            ) {
                throw BindingNotSubmitted("The request destination is outside the approved origin. Nothing was submitted.")
            }
            val builder =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .tag(AtomicBoolean::class.java, AtomicBoolean(false))
            var authorization: String? = null
            request.credential?.let { name ->
                val saved =
                    credential(request.origin, name)?.takeIf { it.origin == request.origin && it.scheme == request.credentialScheme }
                        ?: throw BindingNotSubmitted("Configure the package credential for this approved origin. Nothing was submitted.")
                authorization = saved.authorization()
                builder.header("Authorization", authorization)
            }
            val body =
                request.body?.toRequestBody("application/json; charset=utf-8".toMediaType())
                    ?: if (request.method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody() else null
            val call = client.newCall(builder.method(request.method, body).build())
            call.timeout().timeout(timeoutMillis.coerceIn(1, 60_000), TimeUnit.MILLISECONDS)
            call.execute().use { response ->
                val source = response.body.source()
                val buffer = Buffer()
                while (buffer.size <= request.maxResponseBytes) {
                    if (source.read(buffer, minOf(8192L, request.maxResponseBytes + 1L - buffer.size)) == -1L) break
                }
                check(buffer.size <= request.maxResponseBytes) { "Response exceeded the approved byte limit" }
                val text = buffer.readUtf8()
                val normalized =
                    if (authorization ==
                        null
                    ) {
                        text
                    } else {
                        runCatching { BoundedJson.parse(text, request.maxResponseBytes).toString() }.getOrDefault(text)
                    }
                check(
                    authorization?.let {
                        text.contains(it.substringAfter(" ")) || normalized.contains(it.substringAfter(" "))
                    } != true,
                ) { "Response contained credential material" }
                HttpResponse(response.code, text)
            }
        }

    companion object {
        /** Public services such as OpenStreetMap's refuse anonymous library clients. */
        const val USER_AGENT = "EVA (+https://github.com/colonelpanic8/eva)"
    }
}
