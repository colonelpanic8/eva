package com.colonelpanic.eva.adapters.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One element of a captured screen. [index] is its position in the helper's traversal order. */
data class UiNode(
    val index: Int,
    val className: String,
    val text: String,
    val description: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val focused: Boolean,
    val scrollable: Boolean,
) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2

    /** The label the model sees and the helper re-checks before it delivers input. */
    val label get() = text.ifBlank { description }

    fun simpleClassName() = className.substringAfterLast('.')

    fun describe(): String {
        val traits =
            buildList {
                if (editable) add("editable")
                if (clickable) add("clickable")
                if (scrollable) add("scrollable")
                if (focused) add("focused")
            }
        val quoted = if (label.isBlank()) "" else " \"${label.take(MAX_LABEL_CHARS).escaped()}\""
        val suffix = if (traits.isEmpty()) "" else " ${traits.joinToString(" ")}"
        return "[$index] ${simpleClassName()}$quoted$suffix at ($left,$top)-($right,$bottom)"
    }

    /** Interesting enough to spend projection budget on; pure layout containers are not. */
    fun isAddressable() = clickable || editable || scrollable || label.isNotBlank()

    private companion object {
        const val MAX_LABEL_CHARS = 80

        fun String.escaped() =
            buildString {
                for (character in this@escaped) {
                    append(
                        when (character) {
                            '\\' -> "\\\\"
                            '"' -> "\\\""
                            '\n' -> "\\n"
                            '\r' -> "\\r"
                            '\t' -> "\\t"
                            else -> if (character.isISOControl()) "?" else character
                        },
                    )
                }
            }
    }
}

/** A screen as captured at one moment, held by the backend so the model only handles [reference]. */
data class UiObservation(
    val reference: String,
    val packageName: String,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val nodes: List<UiNode>,
    val capturedAtElapsedMillis: Long,
) {
    fun node(index: Int) = nodes.firstOrNull { it.index == index }

    /**
     * What the model reads. Text only: screenshots stay on the device until a general
     * artifact and disclosure path exists.
     */
    fun project(
        maxNodes: Int = MAX_PROJECTED_NODES,
        maxChars: Int = MAX_PROJECTION_CHARS,
    ): String {
        val addressable = nodes.filter(UiNode::isAddressable)
        val header = "screen $packageName ${width}x$height rotation $rotation (observation $reference)"
        if (addressable.isEmpty()) return "$header\nNo labelled or interactive elements were readable."
        val body = StringBuilder(header)
        var shown = 0
        for (node in addressable) {
            if (shown >= maxNodes) break
            val line = "\n${node.describe()}"
            if (body.length + line.length > maxChars - TRUNCATION_NOTE.length) break
            body.append(line)
            shown++
        }
        if (shown < addressable.size) body.append(TRUNCATION_NOTE)
        return body.toString()
    }

    companion object {
        const val MAX_PROJECTED_NODES = 60
        const val MAX_PROJECTION_CHARS = 3_500
        private const val TRUNCATION_NOTE = "\n… more elements were not shown; scroll or narrow the screen first."

        /** Parses the helper's bounded JSON. Malformed input throws rather than guessing a screen. */
        fun parse(
            payload: JsonObject,
            reference: String,
            capturedAtElapsedMillis: Long,
        ): UiObservation {
            val nodes =
                payload.getValue("nodes").jsonArray.map { entry ->
                    val node = entry.jsonObject
                    UiNode(
                        index = node.getValue("i").jsonPrimitive.int,
                        className = node.string("cls"),
                        text = node.string("text"),
                        description = node.string("desc"),
                        left = node.getValue("l").jsonPrimitive.int,
                        top = node.getValue("t").jsonPrimitive.int,
                        right = node.getValue("r").jsonPrimitive.int,
                        bottom = node.getValue("b").jsonPrimitive.int,
                        clickable = node.flag("clickable"),
                        editable = node.flag("editable"),
                        focused = node.flag("focused"),
                        scrollable = node.flag("scrollable"),
                    )
                }
            return UiObservation(
                reference = reference,
                packageName = payload.string("package"),
                width = payload.getValue("width").jsonPrimitive.int,
                height = payload.getValue("height").jsonPrimitive.int,
                rotation = payload.getValue("rotation").jsonPrimitive.int,
                nodes = nodes,
                capturedAtElapsedMillis = capturedAtElapsedMillis,
            )
        }

        private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

        private fun JsonObject.flag(key: String) = this[key]?.jsonPrimitive?.boolean ?: false
    }
}

class StaleObservationException(
    message: String,
) : IllegalStateException(message)

/**
 * Holds recent observations so an action names a screen EVA captured rather than coordinates the
 * model invented. References expire; the helper still rechecks the target before any input.
 */
class ObservationStore(
    private val elapsedMillis: () -> Long,
    private val maxEntries: Int = MAX_ENTRIES,
    private val lifetimeMillis: Long = LIFETIME_MILLIS,
    private val newReference: (Int) -> String = { "obs-%04x".format(it) },
) {
    private val entries = LinkedHashMap<String, UiObservation>()
    private var issued = 0

    fun record(payload: JsonObject): UiObservation {
        val observation = UiObservation.parse(payload, newReference(++issued), elapsedMillis())
        entries[observation.reference] = observation
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
        return observation
    }

    fun require(reference: String): UiObservation {
        val observation = entries[reference] ?: throw StaleObservationException(UNKNOWN_REFERENCE)
        if (ageMillis(observation) > lifetimeMillis) {
            entries.remove(reference)
            throw StaleObservationException(EXPIRED_REFERENCE)
        }
        return observation
    }

    /** Reserves an observation for one mutation, even when the helper later refuses that mutation. */
    fun consume(reference: String): UiObservation {
        val observation = require(reference)
        entries.remove(reference)
        return observation
    }

    fun ageMillis(observation: UiObservation) = elapsedMillis() - observation.capturedAtElapsedMillis

    companion object {
        const val MAX_ENTRIES = 8
        const val LIFETIME_MILLIS = 180_000L
        const val UNKNOWN_REFERENCE = "That screen observation is not one EVA recorded. Look at the screen again first."
        const val EXPIRED_REFERENCE = "That screen observation is too old to act on. Look at the screen again first."
    }
}

/** Shared JSON configuration for the helper boundary; unknown keys must not crash an action. */
internal val deviceControlJson = Json { ignoreUnknownKeys = true }
