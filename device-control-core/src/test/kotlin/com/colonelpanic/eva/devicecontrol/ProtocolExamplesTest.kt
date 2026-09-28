@file:OptIn(ExperimentalLinkProtocol::class)

package com.colonelpanic.eva.devicecontrol.proto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class ProtocolExamplesTest(
    private val file: File,
) {
    @Test
    fun roundTrip() {
        val text = file.readText()
        when {
            file.name.startsWith("link-") -> checkRoundTrip(LinkMessage.serializer(), text, draft = true)
            file.name.startsWith("action-") -> checkRoundTrip(Action.serializer(), text)
            file.name.startsWith("error-") -> checkRoundTrip(ErrorInfo.serializer(), text)
            file.name.startsWith("result-") -> checkRoundTrip(ActionResult.serializer(), text)
            file.name.startsWith("observation") -> checkRoundTrip(Observation.serializer(), text)
            else -> error("No protocol decoder for ${file.name}")
        }
    }

    private fun <T> checkRoundTrip(
        serializer: KSerializer<T>,
        text: String,
        draft: Boolean = false,
    ) {
        val parsed = json.decodeFromString(serializer, text)
        val encoded = json.encodeToString(serializer, parsed)
        assertEquals(parsed, json.decodeFromString(serializer, encoded))
        val expected = json.parseToJsonElement(text)
        val actual = json.parseToJsonElement(encoded)
        // JsonObject equality ignores key order; arrays and primitive values stay exact.
        assertEquals(expected, if (draft) prune(actual, expected) else actual)
    }

    /** Link drafts predate v1 rev 1 and omit its defaulted fields, which Kotlin now emits. */
    private fun prune(
        element: JsonElement,
        like: JsonElement,
    ): JsonElement =
        when {
            element is JsonObject && like is JsonObject -> {
                JsonObject(element.filterKeys { it in like }.mapValues { (key, value) -> prune(value, like.getValue(key)) })
            }

            element is JsonArray && like is JsonArray && element.size == like.size -> {
                JsonArray(element.zip(like) { e, l -> prune(e, l) })
            }

            else -> {
                element
            }
        }

    companion object {
        private val json = Json(ProtocolJson) { ignoreUnknownKeys = false }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun examples(): List<Array<File>> {
            val resource = requireNotNull(ProtocolExamplesTest::class.java.getResource("/protocol-v1"))
            val files = File(resource.toURI()).listFiles { file -> file.extension == "json" }!!.sorted()
            assertTrue("Shared examples must be available", files.size >= 37)
            return files.map { arrayOf(it) }
        }
    }
}
