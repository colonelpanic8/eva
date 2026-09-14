package com.colonelpanic.eva.providers

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The evidence field is preserved by both direct providers and the broker relay. */
internal fun CorrelatedToolResult.wireOutcome() =
    buildJsonObject {
        put("status", status)
        val bounded = message.take(2000).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        put("message", bounded)
        data?.let { put("data", it) }
        provenance?.let {
            put(
                "evidence",
                buildJsonObject {
                    put("provenance", it.toJson())
                    put("contentTrust", "external")
                },
            )
        }
    }
