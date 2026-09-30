package com.colonelpanic.eva.capability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.util.Collections

/** Strict bounded JSON with duplicate-key rejection before map construction. */
object BoundedJson {
    fun parse(
        text: String,
        maxBytes: Int,
    ): JsonElement {
        require(text.length <= maxBytes && text.toByteArray(Charsets.UTF_8).size <= maxBytes) { "JSON exceeds byte limit" }
        return Reader(text).read()
    }

    fun freeze(value: JsonElement): JsonElement =
        when (value) {
            is JsonObject -> JsonObject(Collections.unmodifiableMap(value.mapValues { freeze(it.value) }))
            is JsonArray -> JsonArray(Collections.unmodifiableList(value.map(::freeze)))
            else -> value
        }

    fun canonical(value: JsonElement): String =
        when (value) {
            is JsonObject -> {
                value.toSortedMap().entries.joinToString(",", "{", "}") { (key, child) ->
                    "${JsonPrimitive(key)}:${canonical(child)}"
                }
            }

            is JsonArray -> {
                value.joinToString(",", "[", "]", transform = ::canonical)
            }

            else -> {
                value.toString()
            }
        }

    fun digest(value: JsonElement): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(canonical(value).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun validUnicode(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char.isHighSurrogate()) {
                if (index == value.length || !value[index++].isLowSurrogate()) return false
            } else if (char.isLowSurrogate()) {
                return false
            }
        }
        return true
    }

    private class Reader(
        private val text: String,
    ) {
        private var at = 0

        fun read(): JsonElement {
            val value = value(0)
            whitespace()
            require(at == text.length) { "Trailing JSON content" }
            return value
        }

        private fun value(depth: Int): JsonElement {
            require(depth <= 16) { "JSON nesting limit exceeded" }
            whitespace()
            require(at < text.length) { "Incomplete JSON" }
            return when (text[at]) {
                '{' -> {
                    at++
                    val entries = linkedMapOf<String, JsonElement>()
                    if (!consume('}')) {
                        do {
                            whitespace()
                            val key = string()
                            require(!entries.containsKey(key)) { "Duplicate JSON key" }
                            require(consume(':')) { "Missing colon" }
                            entries[key] = value(depth + 1)
                        } while (consume(','))
                        require(consume('}')) { "Unclosed object" }
                    }
                    JsonObject(entries)
                }

                '[' -> {
                    at++
                    val entries = mutableListOf<JsonElement>()
                    if (!consume(']')) {
                        do {
                            entries.add(value(depth + 1))
                        } while (consume(','))
                        require(consume(']')) { "Unclosed array" }
                    }
                    JsonArray(entries)
                }

                '"' -> {
                    JsonPrimitive(string())
                }

                else -> {
                    val start = at
                    while (at < text.length && text[at] !in ",]} \t\r\n") at++
                    val token = text.substring(start, at)
                    require(token in setOf("true", "false", "null") || NUMBER.matches(token)) { "Invalid JSON scalar" }
                    if (NUMBER.matches(token)) require(token.toDouble().isFinite()) { "Nonfinite number" }
                    Json.parseToJsonElement(token)
                }
            }
        }

        private fun string(): String {
            require(at < text.length && text[at] == '"') { "Expected string" }
            val start = at++
            while (at < text.length) {
                when (text[at++]) {
                    '\\' -> {
                        at++
                    }

                    '"' -> {
                        val decoded = Json.decodeFromString<String>(text.substring(start, at))
                        require(validUnicode(decoded)) { "Invalid Unicode" }
                        return decoded
                    }
                }
            }
            error("Unclosed string")
        }

        private fun whitespace() {
            while (at < text.length && text[at] in " \t\r\n") at++
        }

        private fun consume(char: Char): Boolean {
            whitespace()
            if (at == text.length || text[at] != char) return false
            at++
            return true
        }
    }

    private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
}
