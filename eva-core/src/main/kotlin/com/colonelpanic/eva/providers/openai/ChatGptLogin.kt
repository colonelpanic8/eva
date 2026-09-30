package com.colonelpanic.eva.providers.openai

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.decodeBase64
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * A ChatGPT subscription pays for these requests, so nothing here is a metered API key.
 * The account issues tokens to the same public client the official command-line client
 * uses; EVA cannot mint a client of its own against this account.
 */
object ChatGpt {
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val ISSUER = "https://auth.openai.com"

    /** Subscription requests go to the account's own backend, not the public API host. */
    const val BASE_URL = "https://chatgpt.com/backend-api/codex"
    const val ORIGINATOR = "eva"
    const val ACCOUNT_LABEL = "ChatGPT subscription"
}

/** A one-time code the phone shows and the person approves on whatever device has a browser. */
data class ChatGptDeviceCode(
    val userCode: String,
    val verificationUrl: String,
    val deviceAuthId: String,
    val pollSeconds: Long,
)

data class ChatGptTokens(
    val idToken: String,
    val accessToken: String,
    val refreshToken: String,
    val accountId: String?,
    val email: String?,
    val plan: String?,
    val expiresAt: Long,
)

/**
 * Device-code login: the phone asks for a code, the person approves it in a browser, and the
 * phone polls until the account hands over tokens. No browser or redirect is needed on the
 * phone itself, so the same flow works from the assistant surface or a headless install.
 */
class ChatGptLogin(
    private val client: OkHttpClient = signInClient(),
    private val issuer: String = ChatGpt.ISSUER,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun requestCode(): ChatGptDeviceCode {
        val body = buildJsonObject { put("client_id", ChatGpt.CLIENT_ID) }
        val response =
            postJson("$issuer/api/accounts/deviceauth/usercode", body) { code, text ->
                check(code != 404) { "This account cannot sign in by device code. Use an API key instead." }
                error(authErrorMessage(code, text))
            }
        // The interval arrives as a string, and a missing one still needs a sane floor.
        val interval = (response.str("interval")?.trim()?.toLongOrNull() ?: 5L).coerceIn(1L, 60L)
        return ChatGptDeviceCode(
            userCode = requireNotNull(response.str("user_code")) { "The account returned no sign-in code." },
            verificationUrl = "$issuer/codex/device",
            deviceAuthId = requireNotNull(response.str("device_auth_id")) { "The account returned no sign-in code." },
            pollSeconds = interval,
        )
    }

    /**
     * Polls until the code is approved. A pending code answers 403 or 404, so those are the
     * wait states rather than failures; anything else is reported as it arrived. The person is
     * approving on another device, so a poll that cannot reach the account is another wait
     * state: giving up on the first one would abandon a sign-in that is already succeeding.
     */
    suspend fun awaitApproval(code: ChatGptDeviceCode): ChatGptTokens {
        val attempts = (APPROVAL_WINDOW_SECONDS / code.pollSeconds).coerceAtLeast(1)
        val body =
            buildJsonObject {
                put("device_auth_id", code.deviceAuthId)
                put("user_code", code.userCode)
            }
        var reached = false
        var unreachable: IOException? = null
        repeat(attempts.toInt()) {
            var pending = false
            val granted =
                try {
                    postJson("$issuer/api/accounts/deviceauth/token", body) { status, text ->
                        if (status == 403 || status == 404) {
                            pending = true
                            JsonObject(emptyMap())
                        } else {
                            error(authErrorMessage(status, text))
                        }
                    }.also { reached = true }
                } catch (error: IOException) {
                    unreachable = error
                    pending = true
                    JsonObject(emptyMap())
                }
            if (!pending) return exchange(granted)
            delay(code.pollSeconds * 1000)
        }
        // Only call it a network failure when no poll ever got an answer.
        unreachable?.takeUnless { reached }?.let { throw it }
        error("That sign-in code expired before it was approved.")
    }

    /** Trades the approved code for tokens, using the verifier the account generated for it. */
    private suspend fun exchange(granted: JsonObject): ChatGptTokens {
        val form =
            listOf(
                "grant_type" to "authorization_code",
                "code" to granted.str("authorization_code").orEmpty(),
                "redirect_uri" to "$issuer/deviceauth/callback",
                "client_id" to ChatGpt.CLIENT_ID,
                "code_verifier" to granted.str("code_verifier").orEmpty(),
            ).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
        val request =
            Request
                .Builder()
                .url("$issuer/oauth/token")
                .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
        return tokens(execute(request) { code, text -> error(authErrorMessage(code, text)) }, previous = null)
    }

    /** Refreshes an expiring access token. Any field the account omits keeps its previous value. */
    suspend fun refresh(previous: ChatGptTokens): ChatGptTokens {
        val body =
            buildJsonObject {
                put("client_id", ChatGpt.CLIENT_ID)
                put("grant_type", "refresh_token")
                put("refresh_token", previous.refreshToken)
            }
        val response =
            postJson("$issuer/oauth/token", body) { code, text ->
                error(authErrorMessage(code, text))
            }
        return tokens(response, previous)
    }

    private fun tokens(
        response: JsonObject,
        previous: ChatGptTokens?,
    ): ChatGptTokens {
        val idToken = response.str("id_token") ?: previous?.idToken
        val accessToken = response.str("access_token") ?: previous?.accessToken
        check(idToken != null && accessToken != null) { "The account returned no usable tokens." }
        val claims = chatGptClaims(idToken)
        return ChatGptTokens(
            idToken = idToken,
            accessToken = accessToken,
            refreshToken = response.str("refresh_token") ?: previous?.refreshToken.orEmpty(),
            accountId = claims.accountId,
            email = claims.email,
            plan = claims.plan,
            expiresAt = expiryOf(accessToken) ?: 0L,
        )
    }

    private suspend fun postJson(
        url: String,
        body: JsonObject,
        onFailure: (Int, String) -> JsonObject,
    ): JsonObject {
        val request =
            Request
                .Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        return execute(request, onFailure)
    }

    private suspend fun execute(
        request: Request,
        onFailure: (Int, String) -> JsonObject,
    ): JsonObject =
        withContext(ioDispatcher) {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) return@use onFailure(response.code, text)
                runCatching { json.parseToJsonElement(text).jsonObject }
                    .getOrElse { error("The account returned an unreadable sign-in response.") }
            }
        }

    private companion object {
        const val APPROVAL_WINDOW_SECONDS = 15L * 60
    }
}

/** A stalled sign-in request should give up in time to poll again, not sit on socket defaults. */
private fun signInClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

internal class ChatGptClaims(
    val accountId: String?,
    val email: String?,
    val plan: String?,
)

/** Reads the account details out of an ID token. The token's signature is the account's to check. */
internal fun chatGptClaims(idToken: String): ChatGptClaims {
    val claims = jwtClaims(idToken) ?: return ChatGptClaims(null, null, null)
    val auth = claims.obj("https://api.openai.com/auth")
    return ChatGptClaims(
        accountId = auth?.str("chatgpt_account_id"),
        email = claims.str("email") ?: claims.obj("https://api.openai.com/profile")?.str("email"),
        plan = auth?.str("chatgpt_plan_type"),
    )
}

/** Epoch milliseconds at which a token stops being accepted, when it says so. */
internal fun expiryOf(jwt: String): Long? =
    (jwtClaims(jwt)?.get("exp") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()?.times(1000)

private fun jwtClaims(jwt: String): JsonObject? {
    val payload = jwt.split(".").getOrNull(1) ?: return null
    val decoded = payload.decodeBase64()?.utf8() ?: return null
    return runCatching { Json.parseToJsonElement(decoded).jsonObject }.getOrNull()
}

/** Sign-in failures carry their reason under differing keys; show whichever one arrived. */
internal fun authErrorMessage(
    code: Int,
    body: String,
): String {
    val parsed = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
    val message =
        parsed?.str("error_description")
            ?: parsed?.str("detail")
            ?: parsed?.obj("error")?.str("message")
            ?: parsed?.str("error")
    return "ChatGPT sign-in failed ($code): ${(message ?: body).trim().take(200)}"
}
