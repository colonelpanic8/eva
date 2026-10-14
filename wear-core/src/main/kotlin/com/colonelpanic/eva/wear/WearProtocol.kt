package com.colonelpanic.eva.wear

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object WearProtocol {
    const val CONFIG = "/eva/wrist/config"
    const val ENABLE = "/eva/wrist/enable"
    const val REFRESH = "/eva/wrist/refresh"
    const val START = "/eva/wrist/start"
    const val RESULT = "/eva/wrist/result"
    const val TRACE = "/eva/wrist/trace/"
    const val PHONE_CAPABILITY = "eva_phone_voice"
    val json = Json { encodeDefaults = true }

    inline fun <reified T> encode(value: T): ByteArray = json.encodeToString(value).toByteArray(Charsets.UTF_8)

    inline fun <reified T> decode(bytes: ByteArray): T = json.decodeFromString(bytes.toString(Charsets.UTF_8))
}

@Serializable
data class WatchStartRequest(
    val id: String,
    val atMillis: Long,
)

@Serializable
data class WatchStartResult(
    val id: String,
    val status: String,
    val message: String,
    val configuration: WatchConfiguration? = null,
)

@Serializable
data class WatchGestureEvent(
    val detected: Boolean,
    val reason: String,
    val sensorMillis: Long,
    val axis: Int,
    val pulses: List<RotationPulseFeatures>,
    val candidate: RotationPulseFeatures?,
    val requiredPulses: Int,
)

@Serializable
data class RotationPulseFeatures(
    val startMillis: Long,
    val durationMillis: Long,
    val direction: Int,
    val peakRate: Double,
    val angleRadians: Double,
    val axisDominance: Double,
    val accelerationDeviation: Double,
)

@Serializable
data class WatchTraceRecord(
    val id: String,
    val atMillis: Long,
    val event: WatchGestureEvent,
)

fun traceGesture(
    record: WatchTraceRecord,
    node: String? = null,
) {
    val e = record.event
    val fields =
        mutableListOf<Pair<String, Any?>>(
            "id" to record.id,
            "node" to node,
            "watchAtMillis" to record.atMillis,
            "sensorMillis" to e.sensorMillis,
            "axis" to e.axis,
            "reason" to e.reason,
            "validPulseCount" to e.pulses.size,
            "requiredPulses" to e.requiredPulses,
        )
    val features = e.pulses.mapIndexed { index, pulse -> "pulse${index + 1}" to pulse } + listOf("candidate" to e.candidate)
    e.pulses.zipWithNext().forEachIndexed { index, (a, b) ->
        fields += "interval${index + 1}Millis" to b.startMillis - a.startMillis
    }
    if (e.candidate != null && e.pulses.isNotEmpty()) {
        fields += "candidate.intervalMillis" to e.candidate.startMillis - e.pulses.last().startMillis
    }
    features.forEach { (name, f) ->
        if (f != null) {
            fields +=
                listOf(
                    "$name.startMillis" to f.startMillis,
                    "$name.durationMillis" to f.durationMillis,
                    "$name.direction" to f.direction,
                    "$name.peakRate" to f.peakRate,
                    "$name.angleRadians" to f.angleRadians,
                    "$name.axisDominance" to f.axisDominance,
                    "$name.accelerationDeviation" to f.accelerationDeviation,
                )
        }
    }
    com.colonelpanic.eva.diagnostics.EvaTrace.info(
        if (e.detected) "wear.gesture.detected" else "wear.gesture.near_miss",
        *fields.toTypedArray(),
    )
}

@Serializable
data class WatchConfiguration(
    val revision: Long,
    val value: WearGestureConfiguration,
)

@Serializable
data class WatchSettingsRequest(
    val id: String,
    val enabled: Boolean,
)
