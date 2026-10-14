package com.colonelpanic.eva.wear

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/** Two opposite, sustained rotations about one configured strap axis, timed by the sensor clock. */
class DoubleTwistDetector(
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
    private var first: TwistFeatures? = null
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
            a: TwistFeatures?,
            b: TwistFeatures? = null,
            detected: Boolean = false,
        ) {
            events +=
                WatchGestureEvent(
                    detected,
                    reason,
                    millis,
                    config.axis,
                    a,
                    b,
                    b?.let { it.startMillis - (a?.startMillis ?: it.startMillis) },
                )
        }
        val previous = previousMillis
        previousMillis = millis
        val dt = previous?.let { millis - it } ?: 0
        if (listOf(x, y, z, accelerationDeviation).any { !it.isFinite() } ||
            (previous != null && (dt <= 0 || dt > config.maxSampleGapMillis))
        ) {
            if (pulse != null || first != null) emit("sample_gap", first, pulse?.features(previous ?: millis))
            pulse = null
            first = null
            blockedDirection = null
            previousRate = 0.0
            return events
        }
        if (millis < refractoryUntil) return events
        val rate = doubleArrayOf(x, y, z)[config.axis]
        val magnitude = sqrt(x * x + y * y + z * z)
        if (abs(rate) < config.releaseRate || rate.sign.toInt() != blockedDirection) blockedDirection = null
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
            if (invalid != null) {
                emit(invalid, first, features)
                first = null
            } else {
                val a = first
                if (a == null) {
                    first = features
                } else {
                    val interval = features.startMillis - a.startMillis
                    val reason =
                        when {
                            features.direction == a.direction -> "same_direction"
                            interval !in config.minIntervalMillis..config.maxIntervalMillis -> "twist_interval"
                            millis - a.startMillis > config.maxGestureMillis -> "gesture_duration"
                            else -> "double_twist"
                        }
                    val detected = reason == "double_twist"
                    emit(reason, a, features, detected)
                    first = null
                    if (detected) refractoryUntil = millis + config.refractoryMillis
                }
            }
        }
        first?.let {
            if (pulse == null && millis - it.startMillis > config.maxIntervalMillis) {
                emit("second_twist_missing", it)
                first = null
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

    private fun Pulse.features(end: Long) = TwistFeatures(start, end - start, direction, peak, angle, dominance, acceleration)
}
