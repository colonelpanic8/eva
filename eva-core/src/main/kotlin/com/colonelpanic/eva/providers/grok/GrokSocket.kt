package com.colonelpanic.eva.providers.grok

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

internal interface GrokSocket : AutoCloseable {
    val events: Flow<String>

    fun send(event: String)
}

internal class OkHttpGrokSocket(
    apiKey: String,
    model: String,
    client: OkHttpClient,
    endpoint: String = "https://api.x.ai/v1/realtime",
) : GrokSocket {
    private val incoming = Channel<String>(Channel.UNLIMITED)
    override val events = incoming.receiveAsFlow()
    private val socket =
        client.newWebSocket(
            Request
                .Builder()
                .url(
                    endpoint
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("model", model)
                        .build(),
                ).header("Authorization", "Bearer $apiKey")
                .build(),
            object : WebSocketListener() {
                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    incoming.trySend(text)
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    incoming.close(
                        IllegalStateException(
                            "Grok voice connection failed${response?.let {
                                " (HTTP ${it.code})"
                            }.orEmpty()}. Check the xAI key, credits, and network.",
                        ),
                    )
                }

                override fun onClosing(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    webSocket.close(code, null)
                    incoming.close(IllegalStateException("Grok voice connection closed (code $code)."))
                }
            },
        )

    override fun send(event: String) {
        check(socket.send(event)) { "Grok voice connection is closed or its send buffer is full." }
    }

    override fun close() {
        socket.cancel()
        incoming.close()
    }
}

internal fun grokClient(): OkHttpClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
