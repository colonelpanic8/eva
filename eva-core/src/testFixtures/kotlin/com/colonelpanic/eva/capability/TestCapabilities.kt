package com.colonelpanic.eva.capability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** A handoff capability with one free-text argument, standing in for any bundled tool under test. */
object TestCapabilities {
    const val SEARCH = "test.maps.search"

    val search =
        CapabilityDefinition(
            SEARCH,
            "Search maps",
            "Open a map search for a destination.",
            Json
                .parseToJsonElement(
                    """
                    {"type":"object","properties":{"destination":{"type":"string","minLength":1,"maxLength":500}},
                    "required":["destination"],"additionalProperties":false}
                    """,
                ).jsonObject,
            validateOperation = { args ->
                val destination = args.getValue("destination")
                if (destination.isBlank() || destination.any { it.isISOControl() }) "Enter a destination on one line." else null
            },
        )

    fun registry(backend: ExecutionBackend) = CapabilityRegistry(mapOf(SEARCH to backend), listOf(search))
}
