package com.colonelpanic.eva.adapters.declarative

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl

data class BasicCredential(
    val origin: String,
    val username: String,
    val password: String,
) {
    fun encode(): String =
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
            val parsed = url.trim().toHttpUrl()
            require(
                parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty(),
            ) { "Use an HTTPS server origin without credentials." }
            require(parsed.encodedPath == "/" && parsed.query == null && parsed.fragment == null) {
                "Enter only the server origin, without a path or query."
            }
            require(username.isNotEmpty() && ':' !in username && username.none(Char::isISOControl)) { "Enter a valid basic-auth username." }
            require(password.isNotEmpty() && password.none(Char::isISOControl)) { "Enter a password without control characters." }
            require(username.length <= 1024 && password.length <= 4096) { "Credential is too long." }
            return BasicCredential(parsed.toString().removeSuffix("/"), username, password)
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
): PackageDefinition {
    fun replace(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> {
                if (element["kind"] == JsonPrimitive("http")) {
                    JsonObject(element + ("origin" to JsonPrimitive(origin)))
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
