package com.colonelpanic.eva.desktop

import com.colonelpanic.eva.providers.openai.ChatGptLogin
import com.colonelpanic.eva.providers.openai.ChatGptTokenSource
import com.colonelpanic.eva.providers.openai.ChatGptTokens
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * ChatGPT subscription tokens in a file only the user can read. Refreshing is serialized so two
 * requests cannot spend the same refresh token twice.
 */
class ChatGptTokenFile(
    private val file: File,
    private val login: ChatGptLogin = ChatGptLogin(),
    private val now: () -> Long = System::currentTimeMillis,
) : ChatGptTokenSource {
    private val mutex = Mutex()

    val signedIn: Boolean get() = stored() != null

    fun save(tokens: ChatGptTokens) = writePrivately(file, tokens.toJson().toString())

    fun clear() {
        file.delete()
    }

    override suspend fun current(): ChatGptTokens =
        mutex.withLock {
            val tokens = stored() ?: error("Sign in first: run eva-desktop login.")
            if (tokens.expiresAt == 0L || tokens.expiresAt - now() > REFRESH_MARGIN_MILLIS) return@withLock tokens
            val refreshed =
                runCatching {
                    login.refresh(
                        tokens,
                    )
                }.getOrElse { error("The ChatGPT sign-in has expired. Run eva-desktop login.") }
            save(refreshed)
            refreshed
        }

    private fun stored(): ChatGptTokens? =
        file.takeIf { it.isFile }?.let { runCatching { Json.parseToJsonElement(it.readText()).jsonObject.toTokens() }.getOrNull() }

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

    private fun JsonObject.toTokens() =
        ChatGptTokens(
            idToken = text("id_token").orEmpty(),
            accessToken = requireNotNull(text("access_token")),
            refreshToken = text("refresh_token").orEmpty(),
            accountId = text("account_id"),
            email = text("email"),
            plan = text("plan"),
            expiresAt = text("expires_at")?.toLongOrNull() ?: 0L,
        )

    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.content

    private companion object {
        const val REFRESH_MARGIN_MILLIS = 2 * 60 * 1000L
    }
}
