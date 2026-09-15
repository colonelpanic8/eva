package com.colonelpanic.eva.providers

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Matches the extension result budget so provider-side truncation notes survive the trip to the model. */
internal const val MODEL_RESULT_CHARS = 16_384
internal const val MODEL_TRUNCATION_NOTE = "\n[Truncated by EVA: the result exceeded the model result budget]"

internal fun boundedResultText(text: String): String {
    if (text.length <= MODEL_RESULT_CHARS) return text
    val cut = text.take(MODEL_RESULT_CHARS - MODEL_TRUNCATION_NOTE.length)
    return (if (cut.lastOrNull()?.isHighSurrogate() == true) cut.dropLast(1) else cut) + MODEL_TRUNCATION_NOTE
}

/** The evidence field is preserved by both direct providers and the broker relay. */
internal fun CorrelatedToolResult.wireOutcome() =
    buildJsonObject {
        put("status", status)
        put("message", boundedResultText(message))
        data?.let {
            if (it.toString().length <= MODEL_RESULT_CHARS) {
                put("data", it)
            } else {
                put("dataOmitted", "Structured data exceeded the model result budget; the message carries the text form.")
            }
        }
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
