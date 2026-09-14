package com.colonelpanic.eva.adapters.declarative

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
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
    private val credential: (String, String) -> BasicCredential?,
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
            val builder = Request.Builder().url(url).tag(AtomicBoolean::class.java, AtomicBoolean(false))
            request.credential?.let { name ->
                val saved =
                    credential(request.origin, name)?.takeIf { it.origin == request.origin }
                        ?: throw BindingNotSubmitted("Configure the package credential for this approved origin. Nothing was submitted.")
                builder.header("Authorization", Credentials.basic(saved.username, saved.password, Charsets.UTF_8))
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
                HttpResponse(response.code, buffer.readUtf8())
            }
        }
}
