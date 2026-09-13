package com.colonelpanic.eva.data

import android.content.Context
import com.colonelpanic.eva.providers.openai.ChatGptLogin
import com.colonelpanic.eva.providers.openai.ChatGptTokenSource
import com.colonelpanic.eva.providers.openai.ChatGptTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What the app can say about the signed-in account without handling its tokens. */
data class ChatGptAccount(
    val email: String?,
    val plan: String?,
) {
    /** "ChatGPT Pro · me@example.com" when both are known, and still useful when neither is. */
    val description: String
        get() =
            listOfNotNull(
                plan?.takeIf { it.isNotBlank() }?.let { "ChatGPT ${it.replaceFirstChar(Char::uppercase)}" } ?: "ChatGPT account",
                email?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
}

/**
 * Subscription tokens, encrypted with the same non-exportable Keystore key as an API key and
 * excluded from backup. Refreshing is serialized so two sessions starting at once cannot spend
 * the same refresh token twice.
 */
class ChatGptAccountStore(
    context: Context,
    private val login: ChatGptLogin = ChatGptLogin(),
    private val now: () -> Long = System::currentTimeMillis,
) : ChatGptTokenSource {
    private val secrets = SecretStore(context)
    private val mutex = Mutex()
    private val mutableAccount = MutableStateFlow(stored()?.account())

    val account: StateFlow<ChatGptAccount?> = mutableAccount.asStateFlow()

    val signedIn: Boolean get() = mutableAccount.value != null

    fun save(tokens: ChatGptTokens) {
        secrets.write(TOKENS, tokens.toJson().toString())
        mutableAccount.value = tokens.account()
    }

    fun clear() {
        secrets.clear(TOKENS)
        mutableAccount.value = null
    }

    override suspend fun current(): ChatGptTokens =
        mutex.withLock {
            val tokens = stored() ?: error("Sign in with ChatGPT on this phone, or add an API key.")
            if (tokens.expiresAt == 0L || tokens.expiresAt - now() > REFRESH_MARGIN_MILLIS) return@withLock tokens
            val refreshed =
                runCatching { login.refresh(tokens) }
                    .getOrElse { error("That ChatGPT sign-in has expired. Sign in again.") }
            save(refreshed)
            refreshed
        }

    private fun stored(): ChatGptTokens? {
        val raw = secrets.read(TOKENS) ?: return null
        return runCatching { Json.parseToJsonElement(raw).jsonObject.toTokens() }.getOrNull()
    }

    private companion object {
        const val TOKENS = "chatgpt.tokens"
        const val REFRESH_MARGIN_MILLIS = 2 * 60 * 1000L
    }
}

private fun ChatGptTokens.account() = ChatGptAccount(email, plan)

private fun ChatGptTokens.toJson(): JsonObject =
    buildJsonObject {
        put("id_token", idToken)
        put("access_token", accessToken)
        put("refresh_token", refreshToken)
        accountId?.let { put("account_id", it) }
        email?.let { put("email", it) }
        plan?.let { put("plan", it) }
        put("expires_at", expiresAt)
    }

private fun JsonObject.toTokens(): ChatGptTokens =
    ChatGptTokens(
        idToken = text("id_token").orEmpty(),
        accessToken = requireNotNull(text("access_token")),
        refreshToken = text("refresh_token").orEmpty(),
        accountId = text("account_id"),
        email = text("email"),
        plan = text("plan"),
        expiresAt = text("expires_at")?.toLongOrNull() ?: 0L,
    )

private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content
