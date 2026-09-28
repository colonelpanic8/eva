package com.colonelpanic.eva.devicecontrol.portal

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

interface PortalTransport {
    suspend fun state(): JsonObject

    suspend fun command(command: PortalCommand)

    suspend fun screenshot(): ByteArray
}

internal class PortalCommandFailure(
    val detail: String,
) : Exception("Portal refused the command")

internal class DegradedSnapshot : Exception("Portal returned a cached tree")

internal class NoActiveWindow : Exception("No active window")

internal class CaptureRejected(
    val secure: Boolean,
) : Exception("Portal refused the screenshot")

/** Same-phone HTTP only; credentials are read at request time and never appear in a URL. */
class PortalClient(
    port: Int = 8080,
    private val token: () -> String,
    client: OkHttpClient = OkHttpClient(),
) : PortalTransport {
    init {
        require(port in 1..65535)
    }

    private val origin = "http://127.0.0.1:$port".toHttpUrl()
    private val http =
        client
            .newBuilder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()

    override suspend fun state(): JsonObject {
        val value =
            try {
                result(request("/state_full?filter=true"))
            } catch (error: PortalCommandFailure) {
                when {
                    "degraded" in error.detail -> throw DegradedSnapshot()
                    "No active window" in error.detail -> throw NoActiveWindow()
                    else -> throw error
                }
            }
        val state = decode(value) as? JsonObject ?: throw IOException("Invalid Portal state")
        if (state["degraded"]?.jsonPrimitive?.booleanOrNull == true) throw DegradedSnapshot()
        return state
    }

    override suspend fun command(command: PortalCommand) {
        require(command.method in setOf("tap", "swipe", "global", "app", "app/deep-link", "keyboard/key", "keyboard/input"))
        result(request("/${command.method}", command.params))
    }

    override suspend fun screenshot(): ByteArray {
        val bytes = request("/screenshot?hideOverlay=true")
        val png =
            if (bytes.take(8).toByteArray().contentEquals(PNG_SIGNATURE)) {
                bytes
            } else {
                val encoded =
                    try {
                        result(bytes).jsonPrimitive.content
                    } catch (error: PortalCommandFailure) {
                        throw CaptureRejected("Secure window" in error.detail)
                    }
                Base64.getDecoder().decode(encoded)
            }
        require(png.size >= 24 && png.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) { "Invalid PNG" }
        require(png.copyOfRange(12, 16).toString(Charsets.US_ASCII) == "IHDR") { "Missing PNG header" }
        return png
    }

    private fun decode(value: JsonElement): JsonElement =
        if (value is JsonPrimitive && value.isString) {
            runCatching { Json.parseToJsonElement(value.content) }.getOrDefault(value)
        } else {
            value
        }

    private fun result(bytes: ByteArray): JsonElement {
        val body = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        if (body["status"]?.jsonPrimitive?.content != "success") {
            throw PortalCommandFailure(body["error"]?.jsonPrimitive?.content.orEmpty())
        }
        return body["result"] ?: JsonNull
    }

    private suspend fun request(
        path: String,
        params: JsonObject? = null,
    ): ByteArray {
        val credential = token().trim()
        check(credential.isNotBlank()) { "Provision the Portal bearer token on this device." }
        val builder = Request.Builder().url(checkNotNull(origin.resolve(path))).header("Authorization", "Bearer $credential")
        if (params != null) builder.post(asciiJson(params).toRequestBody("application/json".toMediaType()))
        val call = http.newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resumeWithException(IOException("Portal transport failed"))
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        continuation.resumeWith(
                            runCatching {
                                response.use {
                                    check(it.isSuccessful) { "Portal HTTP ${it.code}" }
                                    val source = it.body.source()
                                    check(!source.request(MAX_RESPONSE_BYTES + 1)) { "Portal response exceeds the byte limit" }
                                    source.readByteArray()
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    internal companion object {
        const val MAX_RESPONSE_BYTES = 24L * 1024 * 1024
        val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

        /** Portal 0.7.25 reads Content-Length as characters, so request bodies must be ASCII. */
        fun asciiJson(value: JsonObject) =
            buildString {
                value.toString().forEach { char ->
                    if (char.code > 127) append("\\u%04x".format(char.code)) else append(char)
                }
            }
    }
}
