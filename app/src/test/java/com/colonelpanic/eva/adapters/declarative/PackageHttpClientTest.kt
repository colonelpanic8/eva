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
}
