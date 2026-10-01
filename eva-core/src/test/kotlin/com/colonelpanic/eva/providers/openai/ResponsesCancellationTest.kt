package com.colonelpanic.eva.providers.openai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ResponsesCancellationTest {
    @Test fun cancellationCancelsOkHttpWhileSseBodyIsStillOpen() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/responses") { exchange ->
                exchange.requestBody.readBytes()
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(
                    "data: {\"type\":\"response.created\",\"response\":{\"id\":\"r\",\"status\":\"in_progress\"}}\n\n".toByteArray(),
                )
                exchange.responseBody.flush()
                started.complete(Unit)
                release.await(10, TimeUnit.SECONDS)
                exchange.close()
            }
            server.start()
            val client =
                OkHttpClient
                    .Builder()
                    .eventListener(
                        object : EventListener() {
                            override fun canceled(call: Call) {
                                cancelled.complete(Unit)
                            }
                        },
                    ).build()
            val tokens = ChatGptTokens("", "test-token", "", null, null, null, Long.MAX_VALUE)
            try {
                val job =
                    launch {
                        responsesPost(
                            client,
                            SubscriptionAccess({ tokens }, "1.0.0", "http://127.0.0.1:${server.address.port}"),
                            buildJsonObject {},
                        )
                    }
                withTimeout(3000) {
                    started.await()
                    job.cancelAndJoin()
                    cancelled.await()
                }
                assertTrue(job.isCancelled)
            } finally {
                release.countDown()
                server.stop(0)
            }
        }
}
