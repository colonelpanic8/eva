package com.colonelpanic.eva.wear

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/** A configured even number of alternating strap-axis pulses, timed by the sensor clock. */
class AlternatingTwistDetector(
    private val config: WearGestureConfiguration,
) {
    private data class Pulse(
        val start: Long,
        val direction: Int,
        var peak: Double = 0.0,
        var angle: Double = 0.0,
        var dominance: Double = 1.0,
        var acceleration: Double = 0.0,
    )

    private var previousMillis: Long? = null
    private var previousRate = 0.0
    private var pulse: Pulse? = null
    private var blockedDirection: Int? = null
    private val accepted = mutableListOf<RotationPulseFeatures>()
    private var refractoryUntil = Long.MIN_VALUE

    fun sample(
        millis: Long,
        x: Double,
        y: Double,
        z: Double,
        accelerationDeviation: Double,
    ): List<WatchGestureEvent> {
        val events = mutableListOf<WatchGestureEvent>()

        fun emit(
            reason: String,
            candidate: RotationPulseFeatures? = null,
            detected: Boolean = false,
        ) {
            events +=
                WatchGestureEvent(
                    detected = detected,
                    reason = if (detected) reason else "$reason (${accepted.size}/${config.requiredPulses} valid pulses)",
                    sensorMillis = millis,
                    axis = config.axis,
                    pulses = accepted.toList(),
                    candidate = candidate,
                    requiredPulses = config.requiredPulses,
                )
        }
        val previous = previousMillis
        previousMillis = millis
        val dt = previous?.let { millis - it } ?: 0
        if (listOf(x, y, z, accelerationDeviation).any { !it.isFinite() } ||
            (previous != null && (dt <= 0 || dt > config.maxSampleGapMillis))
        ) {
            if (pulse != null || accepted.isNotEmpty()) emit("sample_gap", pulse?.features(previous ?: millis))
            pulse = null
            accepted.clear()
            blockedDirection = null
            previousRate = 0.0
            return events
        }
        if (millis < refractoryUntil) return events
        val rate = doubleArrayOf(x, y, z)[config.axis]
        val magnitude = sqrt(x * x + y * y + z * z)
        if (abs(rate) < config.releaseRate || rate.sign.toInt() != blockedDirection) blockedDirection = null
        if (accepted.isNotEmpty()) {
            val expired =
                when {
                    millis - accepted.first().startMillis > config.maxGestureMillis -> "gesture_duration"
                    pulse == null && millis - accepted.last().startMillis > config.maxIntervalMillis -> "next_pulse_missing"
                    else -> null
                }
            if (expired != null) {
                emit(expired, pulse?.features(millis))
                if (pulse != null && abs(rate) >= config.releaseRate) blockedDirection = rate.sign.toInt()
                pulse = null
                accepted.clear()
            }
        }
        val current = pulse
        if (current != null &&
            (abs(rate) < config.releaseRate || rate.sign.toInt() != current.direction || millis - current.start > config.maxPulseMillis)
        ) {
            if (millis - current.start > config.maxPulseMillis && abs(rate) >= config.releaseRate &&
                rate.sign.toInt() == current.direction
            ) {
                blockedDirection = current.direction
            }
            val features = current.features(millis)
            pulse = null
            val invalid =
                when {
                    features.durationMillis !in config.minPulseMillis..config.maxPulseMillis -> "pulse_duration"
                    features.peakRate < config.peakRate -> "peak_rate"
                    features.angleRadians < config.minAngleRadians -> "rotation_angle"
                    features.axisDominance < config.minAxisDominance -> "off_axis"
                    features.accelerationDeviation > config.maxAccelerationDeviation -> "linear_vibration"
                    else -> null
                }
            val previousPulse = accepted.lastOrNull()
            val sequenceFailure =
                when {
                    previousPulse == null -> {
                        null
                    }

                    features.direction == previousPulse.direction -> {
                        "same_direction"
                    }

                    features.startMillis - previousPulse.startMillis !in config.minIntervalMillis..config.maxIntervalMillis -> {
                        "pulse_interval"
                    }

                    else -> {
                        null
                    }
                }
            val failure = invalid ?: sequenceFailure
            if (failure != null) {
                emit(failure, features)
                accepted.clear()
            } else {
                accepted += features
                if (accepted.size == config.requiredPulses) {
                    emit("alternating_pulses", detected = true)
                    accepted.clear()
                    refractoryUntil = millis + config.refractoryMillis
                }
            }
        }
        if (millis >= refractoryUntil) {
            if (pulse == null && blockedDirection == null && abs(rate) >= config.candidateRate) pulse = Pulse(millis, rate.sign.toInt())
            pulse?.let {
                it.peak = max(it.peak, abs(rate))
                it.angle += (abs(rate) + if (it === current) abs(previousRate) else 0.0) * 0.5 * dt / 1000.0
                it.dominance = min(it.dominance, if (magnitude > 0) abs(rate) / magnitude else 1.0)
                it.acceleration = max(it.acceleration, accelerationDeviation)
            }
        }
        previousRate = rate
        return events
    }

    private fun Pulse.features(end: Long) = RotationPulseFeatures(start, end - start, direction, peak, angle, dominance, acceleration)
}
