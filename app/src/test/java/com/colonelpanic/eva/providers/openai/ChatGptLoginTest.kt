package com.colonelpanic.eva.providers.openai

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptLoginTest {
    private val claims =
        """
        {"email":"eva@example.test","exp":2000000000,
        "https://api.openai.com/auth":{"chatgpt_account_id":"acct-1","chatgpt_plan_type":"pro"}}
        """.trimIndent()
    private val token = "aGVhZGVy.${claims.encodeUtf8().base64Url()}.c2ln"
    private val sent = mutableMapOf<String, String>()
    private var pendingPolls = 2

    private fun client(vararg overrides: Pair<String, Pair<Int, String>>) =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                val buffer = Buffer().also { chain.request().body?.writeTo(it) }
                sent[path] = buffer.readUtf8()
                val override = overrides.toMap()[path]
                val (code, body) =
                    override ?: when (path) {
                        "/api/accounts/deviceauth/usercode" -> {
                            200 to """{"device_auth_id":"dev-1","user_code":"BB4Z-SVECM","interval":"1"}"""
                        }

                        "/api/accounts/deviceauth/token" -> {
                            if (pendingPolls-- > 0) {
                                403 to """{"detail":"pending"}"""
                            } else {
                                200 to """{"authorization_code":"code-1","code_challenge":"chal","code_verifier":"ver"}"""
                            }
                        }

                        "/oauth/token" -> {
                            200 to """{"id_token":"$token","access_token":"$token","refresh_token":"refresh-2"}"""
                        }

                        else -> {
                            404 to "{}"
                        }
                    }
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("x")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

    private fun login(
        client: OkHttpClient,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ) = ChatGptLogin(client, "https://auth.test", StandardTestDispatcher(scheduler))

    @Test
    fun `an approved code is exchanged for tokens that name the account`() =
        runTest {
            val login = login(client(), testScheduler)
            val code = login.requestCode()
            assertEquals("BB4Z-SVECM", code.userCode)
            assertEquals("https://auth.test/codex/device", code.verificationUrl)

            val tokens = login.awaitApproval(code)
            assertEquals("acct-1", tokens.accountId)
            assertEquals("pro", tokens.plan)
            assertEquals("eva@example.test", tokens.email)
            assertEquals("refresh-2", tokens.refreshToken)
            assertEquals(2000000000L * 1000, tokens.expiresAt)
            val exchange = sent.getValue("/oauth/token")
            assertTrue(exchange.contains("grant_type=authorization_code"))
            assertTrue(exchange.contains("code_verifier=ver"))
            assertTrue(exchange.contains("redirect_uri=https%3A%2F%2Fauth.test%2Fdeviceauth%2Fcallback"))
        }

    @Test
    fun `an account without device login says so instead of reporting a bare status`() =
        runTest {
            val login = login(client("/api/accounts/deviceauth/usercode" to (404 to "{}")), testScheduler)
            val error = runCatching { login.requestCode() }.exceptionOrNull()
            assertEquals("This account cannot sign in by device code. Use an API key instead.", error?.message)
        }

    @Test
    fun `a refresh that returns no new refresh token keeps the one already held`() =
        runTest {
            val login =
                login(
                    client("/oauth/token" to (200 to """{"id_token":"$token","access_token":"$token"}""")),
                    testScheduler,
                )
            val previous =
                ChatGptTokens(token, "stale", "refresh-1", "acct-1", "eva@example.test", "pro", 0)
            val refreshed = login.refresh(previous)
            assertEquals("refresh-1", refreshed.refreshToken)
            assertEquals(token, refreshed.accessToken)
        }

    @Test
    fun `a rejected sign-in reports the reason the account gave`() =
        runTest {
            val login =
                login(
                    client("/api/accounts/deviceauth/token" to (400 to """{"error_description":"code already used"}""")),
                    testScheduler,
                )
            val code = login.requestCode()
            val error = runCatching { login.awaitApproval(code) }.exceptionOrNull()
            assertTrue(error!!.message!!.contains("code already used"))
        }
}
