package com.colonelpanic.eva.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The rules every diagnostic output passes through before it leaves EVA's private storage.
 *
 * - A value stored under a credential-like key (token, secret, password, API key, authorization,
 *   cookie, credential, …) is replaced whole, whatever it contains.
 * - Every [knownSecrets] value (what the device's secret store holds) is replaced wherever it appears.
 * - Recognizable credential shapes are replaced in free text: bearer and basic authorization,
 *   header and `key=value` assignments, credential URL query parameters, URL user info,
 *   OpenAI/GitHub/Slack/Google key prefixes, and JWTs.
 *
 * Message content, arguments, and results are otherwise kept: they are the user's own evidence.
 */
class Redactor(
    knownSecrets: Collection<String> = emptyList(),
) {
    private val secrets =
        knownSecrets
            .map(
                String::trim,
            ).filter { it.length >= MIN_SECRET_LENGTH }
            .distinct()
            .sortedByDescending { it.length }

    fun text(value: String): String {
        var result = value
        secrets.forEach { result = result.replace(it, REDACTED) }
        PATTERNS.forEach { (pattern, replacement) -> result = pattern.replace(result, replacement) }
        return result
    }

    fun json(value: JsonElement): JsonElement =
        when (value) {
            is JsonObject -> {
                JsonObject(
                    value.mapValues { (key, child) ->
                        if (sensitiveKey(key) &&
                            child !is JsonNull
                        ) {
                            redactAll(child)
                        } else {
                            json(child)
                        }
                    },
                )
            }

            is JsonArray -> {
                JsonArray(value.map(::json))
            }

            is JsonPrimitive -> {
                if (value.isString) JsonPrimitive(text(value.content)) else value
            }
        }

    fun arguments(value: Map<String, String>): Map<String, String> =
        value.mapValues { (key, argument) -> if (sensitiveKey(key)) REDACTED else text(argument) }

    private fun redactAll(value: JsonElement): JsonElement =
        when (value) {
            is JsonObject -> JsonObject(value.mapValues { redactAll(it.value) })
            is JsonArray -> JsonArray(value.map(::redactAll))
            else -> JsonPrimitive(REDACTED)
        }

    companion object {
        const val REDACTED = "[redacted]"
        private const val MIN_SECRET_LENGTH = 6

        private val SENSITIVE_WORDS =
            setOf(
                "token",
                "secret",
                "password",
                "passwd",
                "passphrase",
                "authorization",
                "auth",
                "bearer",
                "cookie",
                "cookies",
                "credential",
                "credentials",
                "apikey",
                "signature",
                "otp",
                "pin",
            )
        private val SENSITIVE_PAIRS = setOf("api key", "access key", "private key", "client secret", "session key", "set cookie")

        /** Splits camelCase, snake_case, kebab-case, and dotted keys into lowercase words. */
        fun sensitiveKey(key: String): Boolean {
            val words =
                key
                    .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                    .lowercase()
                    .split(Regex("[^a-z0-9]+"))
                    .filter(String::isNotEmpty)
            return words.any { it in SENSITIVE_WORDS } || words.zipWithNext { a, b -> "$a $b" }.any { it in SENSITIVE_PAIRS }
        }

        private val PATTERNS =
            listOf(
                Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{6,}") to "$1 $REDACTED",
                Regex(
                    "(?i)\\b(authorization|proxy-authorization|x-api-key|api-key|x-auth-token|cookie|set-cookie)(\\s*[:=]\\s*)[^\\r\\n]+",
                ) to
                    "$1$2$REDACTED",
                Regex(
                    "(?i)\\b(access_token|refresh_token|id_token|client_secret|api_key|apikey|token|secret|password|passwd)" +
                        "(\"?\\s*[:=]\\s*\"?)[^\\s\"',;&]+",
                ) to "$1$2$REDACTED",
                Regex("(?i)([?&#](?:key|sig|signature|code|auth|access_token|token|api_key|apikey|secret|password)=)[^&\\s#\"']+") to
                    "$1$REDACTED",
                Regex("(?i)\\b(https?://)[^/\\s:@]+:[^/\\s@]+@") to "$1$REDACTED@",
                Regex("\\bsk-[A-Za-z0-9_-]{16,}") to REDACTED,
                Regex("\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}") to REDACTED,
                Regex("\\bgithub_pat_[A-Za-z0-9_]{20,}") to REDACTED,
                Regex("\\bxox[abprs]-[A-Za-z0-9-]{10,}") to REDACTED,
                Regex("\\bAIza[0-9A-Za-z_-]{30,}") to REDACTED,
                Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}") to REDACTED,
            )
    }
}
