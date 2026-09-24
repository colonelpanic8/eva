package com.colonelpanic.eva.adapters.declarative

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl

sealed interface HttpCredential {
    val origin: String
    val scheme: String

    fun encode(): String

    fun authorization(): String
}

data class BasicCredential(
    override val origin: String,
    val username: String,
    val password: String,
) : HttpCredential {
    override val scheme = "basic"

    override fun authorization() = okhttp3.Credentials.basic(username, password, Charsets.UTF_8)

    override fun toString() = "BasicCredential([redacted])"

    override fun encode(): String =
        JsonObject(
            mapOf(
                "origin" to JsonPrimitive(origin),
                "username" to JsonPrimitive(username),
                "password" to JsonPrimitive(password),
            ),
        ).toString()

    companion object {
        fun create(
            url: String,
            username: String,
            password: String,
        ): BasicCredential {
            val origin = credentialOrigin(url)
            require(username.isNotEmpty() && ':' !in username && username.none(Char::isISOControl)) { "Enter a valid basic-auth username." }
            require(password.isNotEmpty() && password.none(Char::isISOControl)) { "Enter a password without control characters." }
            require(username.length <= 1024 && password.length <= 4096) { "Credential is too long." }
            return BasicCredential(origin, username, password)
        }

        fun decode(value: String): BasicCredential {
            val fields = Json.parseToJsonElement(value).jsonObject
            return create(
                fields.getValue("origin").jsonPrimitive.content,
                fields.getValue("username").jsonPrimitive.content,
                fields.getValue("password").jsonPrimitive.content,
            )
        }
    }
}

/** User approval replaces fixed HTTP origins before validation and digesting, never at invocation. */
fun configurePackage(
    source: PackageDefinition,
    origin: String,
): PackageDefinition = configurePackage(source, source.httpBindings().associate { it.origin to origin })

/** Replaces only explicitly approved source origins before validation and digesting. */
fun configurePackage(
    source: PackageDefinition,
    origins: Map<String, String>,
): PackageDefinition {
    fun replace(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> {
                if (element["kind"] == JsonPrimitive("http")) {
                    val sourceOrigin = element["origin"]?.jsonPrimitive?.content
                    JsonObject(element + ("origin" to JsonPrimitive(origins[sourceOrigin] ?: sourceOrigin.orEmpty())))
                } else {
                    JsonObject(element.mapValues { replace(it.value) })
                }
            }

            is JsonArray -> {
                JsonArray(element.map(::replace))
            }

            else -> {
                element
            }
        }
    return PackageCodec.decode(replace(source.document).toString())
}

fun PackageDefinition.httpBindings(): List<DeclarativeBinding.Http> {
    fun leaves(binding: DeclarativeBinding): List<DeclarativeBinding.Http> =
        when (binding) {
            is DeclarativeBinding.Http -> listOf(binding)
            is DeclarativeBinding.Select -> leaves(binding.present) + leaves(binding.absent)
            else -> emptyList()
        }
    return capabilities.flatMap { leaves(it.binding) }
}

class BearerCredential private constructor(
    override val origin: String,
    private val token: String,
) : HttpCredential {
    override val scheme = "bearer"

    override fun authorization() = "Bearer $token"

    override fun toString() = "BearerCredential([redacted])"

    override fun encode(): String = JsonObject(mapOf("origin" to JsonPrimitive(origin), "token" to JsonPrimitive(token))).toString()

    companion object {
        fun create(
            url: String,
            token: String,
        ): BearerCredential {
            val origin = credentialOrigin(url)
            require(token.length in 1..4096 && Regex("[A-Za-z0-9._~+/-]+=*").matches(token)) {
                "Enter a valid Bearer token without whitespace."
            }
            return BearerCredential(origin, token)
        }

        fun decode(value: String): BearerCredential {
            val fields = Json.parseToJsonElement(value).jsonObject
            return create(fields.getValue("origin").jsonPrimitive.content, fields.getValue("token").jsonPrimitive.content)
        }
    }
}

private fun credentialOrigin(url: String): String {
    val parsed = url.trim().toHttpUrl()
    require(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty()) {
        "Use an HTTPS server origin without credentials."
    }
    require(parsed.encodedPath == "/" && parsed.query == null && parsed.fragment == null) {
        "Enter only the server origin, without a path or query."
    }
    return parsed.toString().removeSuffix("/")
}
