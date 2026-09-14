package com.colonelpanic.eva.providers.spotify

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.toByteString
import java.security.SecureRandom

object Spotify {
    const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
    const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    const val API_URL = "https://api.spotify.com/v1"
    const val REDIRECT_URI = "eva://spotify"
    val SCOPES = listOf("user-modify-playback-state", "user-read-playback-state", "user-read-private")
}

data class SpotifyTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val scope: String?,
)

data class SpotifyProfile(
    val displayName: String?,
    val product: String?,
)

interface SpotifyLoginClient {
    suspend fun exchange(
        clientId: String,
        code: String,
        verifier: String,
    ): SpotifyTokens

    suspend fun profile(accessToken: String): SpotifyProfile
}

interface SpotifyTokenRefresher {
    suspend fun refresh(
        clientId: String,
        previous: SpotifyTokens,
    ): SpotifyTokens
}

class SpotifyLogin(
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : SpotifyLoginClient,
    SpotifyTokenRefresher {
    private val json = Json { ignoreUnknownKeys = true }

    fun authorizationUrl(
        clientId: String,
        challenge: String,
        state: String,
    ): String =
        Spotify.AUTHORIZE_URL
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", Spotify.REDIRECT_URI)
            .addQueryParameter("scope", Spotify.SCOPES.joinToString(" "))
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("state", state)
            .build()
            .toString()

    override suspend fun exchange(
        clientId: String,
        code: String,
        verifier: String,
    ): SpotifyTokens =
        token(
            FormBody
                .Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", Spotify.REDIRECT_URI)
                .add("client_id", clientId)
                .add("code_verifier", verifier)
                .build(),
            previous = null,
        )

    override suspend fun refresh(
        clientId: String,
        previous: SpotifyTokens,
    ): SpotifyTokens =
        token(
            FormBody
                .Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", previous.refreshToken)
                .add("client_id", clientId)
                .build(),
            previous,
        )

    override suspend fun profile(accessToken: String): SpotifyProfile {
        val request =
            Request
                .Builder()
                .url("${Spotify.API_URL}/me")
                .header("Authorization", "Bearer $accessToken")
                .build()
        val response = execute(request)
        return SpotifyProfile(response.text("display_name"), response.text("product"))
    }

    private suspend fun token(
        body: FormBody,
        previous: SpotifyTokens?,
    ): SpotifyTokens {
        val request =
            Request
                .Builder()
                .url(Spotify.TOKEN_URL)
                .post(body)
                .build()
        val response = execute(request)
        val accessToken = response.text("access_token") ?: error("Spotify returned no access token.")
        val refreshToken = response.text("refresh_token") ?: previous?.refreshToken ?: error("Spotify returned no refresh token.")
        val expiresIn = response.text("expires_in")?.toLongOrNull() ?: error("Spotify returned no token lifetime.")
        return SpotifyTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = now() + expiresIn * 1000,
            scope = response.text("scope") ?: previous?.scope,
        )
    }

    private suspend fun execute(request: Request): JsonObject =
        withContext(ioDispatcher) {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) error(spotifySignInError(response.code, body))
                runCatching { json.parseToJsonElement(body).jsonObject }
                    .getOrElse { error("Spotify returned an unreadable response.") }
            }
        }
}

private const val VERIFIER_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
private val secureRandom = SecureRandom()

internal fun spotifyCodeVerifier(): String = randomSpotifyText(64)

internal fun spotifyState(): String = randomSpotifyText(32)

private fun randomSpotifyText(length: Int): String =
    buildString(length) {
        repeat(length) { append(VERIFIER_CHARS[secureRandom.nextInt(VERIFIER_CHARS.length)]) }
    }

internal fun spotifyCodeChallenge(verifier: String): String =
    java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(verifier.toByteArray(Charsets.US_ASCII))
        .toByteString()
        .base64Url()
        .trimEnd('=')

internal fun spotifySignInError(
    code: Int,
    body: String,
): String {
    val parsed = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
    val message = parsed?.text("error_description") ?: parsed?.text("error") ?: body
    return "Spotify sign-in failed ($code): ${message.trim().take(200)}"
}

private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content
