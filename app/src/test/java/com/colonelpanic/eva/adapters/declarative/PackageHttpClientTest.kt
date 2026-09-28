package com.colonelpanic.eva.adapters.declarative

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageHttpClientTest {
    @Test
    fun `client attaches origin scoped credentials disables redirects and retries and bounds output`() =
        runTest {
            var calls = 0
            var body = """{"status":"created"}"""
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        calls++
                        assertEquals("Basic dXNlcjpwYXNz", chain.request().header("Authorization"))
                        assertEquals(PackageHttpClient.USER_AGENT, chain.request().header("User-Agent"))
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(302)
                            .message("Redirect")
                            .header("Location", "https://elsewhere.example/")
                            .body(body.toResponseBody())
                            .build()
                    }.build()
            val host =
                PackageHttpClient({ origin, name ->
                    assertEquals("org-agenda", name)
                    BasicCredential.create(origin, "user", "pass")
                }, client)
            val request = HttpRequest("https://agenda.example", "https://agenda.example/capture", "POST", "{}", "org-agenda", 64)
            assertEquals(302, host.execute(request, 1000).status)
            assertEquals(1, calls)
            body = "x".repeat(65)
            assertTrue(runCatching { host.execute(request, 1000) }.isFailure)
            assertEquals(2, calls)
            assertTrue(
                runCatching { host.execute(request.copy(url = "https://elsewhere.example/capture"), 1000) }.exceptionOrNull()
                    is BindingNotSubmitted,
            )
            assertEquals(2, calls)
            val missing = PackageHttpClient({ _, _ -> null }, client)
            assertTrue(runCatching { missing.execute(request, 1000) }.exceptionOrNull() is BindingNotSubmitted)
            assertEquals(2, calls)
        }

    @Test
    fun `bearer headers stay scoped and credential echoes never reach results`() =
        runTest {
            val token = "test-token/+="
            var calls = 0
            var body = "[]"
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        calls++
                        assertEquals("Bearer $token", chain.request().header("Authorization"))
                        assertTrue(
                            !chain
                                .request()
                                .url
                                .toString()
                                .contains(token),
                        )
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(body.toResponseBody())
                            .build()
                    }.build()
            val saved = BearerCredential.create("https://history.example/", token)
            assertEquals(saved.authorization(), BearerCredential.decode(saved.encode()).authorization())
            assertTrue(!saved.toString().contains(token))
            val request = HttpRequest("https://history.example", "https://history.example/points", "GET", null, "history", 256, "bearer")
            val host = PackageHttpClient({ _, _ -> saved }, client)
            assertEquals("[]", host.execute(request, 1000).body)
            body = "{\"echo\":\"$token\"}"
            assertTrue(runCatching { host.execute(request, 1000) }.exceptionOrNull()?.message?.contains(token) == false)
            body = "{\"echo\":\"test-token" + "\\u002f" + "+=\"}"
            assertTrue(runCatching { host.execute(request, 1000) }.isFailure)
            val before = calls
            listOf<HttpCredential?>(
                null,
                BasicCredential.create(request.origin, "user", "password"),
                BearerCredential.create("https://other.example", token),
            ).forEach { credential ->
                assertTrue(
                    runCatching {
                        PackageHttpClient({ _, _ -> credential }, client).execute(request, 1000)
                    }.exceptionOrNull() is BindingNotSubmitted,
                )
            }
            assertTrue(
                runCatching {
                    host.execute(
                        request.copy(url = "https://other.example/points"),
                        1000,
                    )
                }.exceptionOrNull() is BindingNotSubmitted,
            )
            assertEquals(before, calls)
            listOf("", "a b", "a\r\nInjected: x", "ü", "x".repeat(4097)).forEach { invalid ->
                org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { BearerCredential.create(request.origin, invalid) }
            }
        }
}
