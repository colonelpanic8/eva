package com.colonelpanic.eva.wear

import kotlinx.serialization.Serializable

@Serializable
data class WearGestureConfiguration(
    val enabled: Boolean = false,
    val axis: Int = 1,
    val candidateRate: Double = 2.5,
    val peakRate: Double = 5.5,
    val releaseRate: Double = 1.5,
    val minPulseMillis: Long = 45,
    val maxPulseMillis: Long = 180,
    val minAngleRadians: Double = 0.45,
    val minAxisDominance: Double = 0.85,
    val minIntervalMillis: Long = 120,
    val maxIntervalMillis: Long = 400,
    val maxGestureMillis: Long = 550,
    val refractoryMillis: Long = 2500,
    val maxSampleGapMillis: Long = 60,
    val maxAccelerationDeviation: Double = 8.0,
) {
    init {
        require(axis in 0..2) { "wearGesture.axis must be 0 (X), 1 (Y), or 2 (Z)." }
        require(
            listOf(candidateRate, peakRate, releaseRate, minAngleRadians, minAxisDominance, maxAccelerationDeviation).all {
                it.isFinite() &&
                    it > 0
            },
        ) {
            "Wear gesture rates, angle, dominance and acceleration bound must be finite and positive."
        }
        require(releaseRate < candidateRate && candidateRate < peakRate) { "Wear gesture rates must satisfy release < candidate < peak." }
        require(minPulseMillis > 0 && maxPulseMillis >= minPulseMillis) { "Invalid wear gesture pulse duration range." }
        require(minIntervalMillis > 0 && maxIntervalMillis >= minIntervalMillis && maxGestureMillis >= maxIntervalMillis) {
            "Invalid wear gesture timing window."
        }
        require(minAxisDominance <= 1 && refractoryMillis >= maxGestureMillis && maxSampleGapMillis > 0) {
            "Invalid wear gesture dominance, refractory period or sample gap."
        }
    }
}

/** Nullable fields preserve independent include overrides, including an explicit false. */
@Serializable
data class WearGesturePatch(
    val enabled: Boolean? = null,
    val axis: Int? = null,
    val candidateRate: Double? = null,
    val peakRate: Double? = null,
    val releaseRate: Double? = null,
    val minPulseMillis: Long? = null,
    val maxPulseMillis: Long? = null,
    val minAngleRadians: Double? = null,
    val minAxisDominance: Double? = null,
    val minIntervalMillis: Long? = null,
    val maxIntervalMillis: Long? = null,
    val maxGestureMillis: Long? = null,
    val refractoryMillis: Long? = null,
    val maxSampleGapMillis: Long? = null,
    val maxAccelerationDeviation: Double? = null,
) {
    fun materialize(): WearGestureConfiguration {
        val defaults = WearGestureConfiguration()
        return WearGestureConfiguration(
            enabled = enabled ?: defaults.enabled,
            axis = axis ?: defaults.axis,
            candidateRate = candidateRate ?: defaults.candidateRate,
            peakRate = peakRate ?: defaults.peakRate,
            releaseRate = releaseRate ?: defaults.releaseRate,
            minPulseMillis = minPulseMillis ?: defaults.minPulseMillis,
            maxPulseMillis = maxPulseMillis ?: defaults.maxPulseMillis,
            minAngleRadians = minAngleRadians ?: defaults.minAngleRadians,
            minAxisDominance = minAxisDominance ?: defaults.minAxisDominance,
            minIntervalMillis = minIntervalMillis ?: defaults.minIntervalMillis,
            maxIntervalMillis = maxIntervalMillis ?: defaults.maxIntervalMillis,
            maxGestureMillis = maxGestureMillis ?: defaults.maxGestureMillis,
            refractoryMillis = refractoryMillis ?: defaults.refractoryMillis,
            maxSampleGapMillis = maxSampleGapMillis ?: defaults.maxSampleGapMillis,
            maxAccelerationDeviation = maxAccelerationDeviation ?: defaults.maxAccelerationDeviation,
        )
    }

    fun over(base: WearGesturePatch?): WearGesturePatch =
        WearGesturePatch(
            enabled = enabled ?: base?.enabled,
            axis = axis ?: base?.axis,
            candidateRate = candidateRate ?: base?.candidateRate,
            peakRate = peakRate ?: base?.peakRate,
            releaseRate = releaseRate ?: base?.releaseRate,
            minPulseMillis = minPulseMillis ?: base?.minPulseMillis,
            maxPulseMillis = maxPulseMillis ?: base?.maxPulseMillis,
            minAngleRadians = minAngleRadians ?: base?.minAngleRadians,
            minAxisDominance = minAxisDominance ?: base?.minAxisDominance,
            minIntervalMillis = minIntervalMillis ?: base?.minIntervalMillis,
            maxIntervalMillis = maxIntervalMillis ?: base?.maxIntervalMillis,
            maxGestureMillis = maxGestureMillis ?: base?.maxGestureMillis,
            refractoryMillis = refractoryMillis ?: base?.refractoryMillis,
            maxSampleGapMillis = maxSampleGapMillis ?: base?.maxSampleGapMillis,
            maxAccelerationDeviation = maxAccelerationDeviation ?: base?.maxAccelerationDeviation,
        )

    companion object {
        fun diff(
            value: WearGestureConfiguration,
            base: WearGesturePatch?,
        ): WearGesturePatch? =
            WearGesturePatch(
                enabled = value.enabled.takeIf { it != base?.enabled },
                axis = value.axis.takeIf { it != base?.axis },
                candidateRate = value.candidateRate.takeIf { it != base?.candidateRate },
                peakRate = value.peakRate.takeIf { it != base?.peakRate },
                releaseRate = value.releaseRate.takeIf { it != base?.releaseRate },
                minPulseMillis = value.minPulseMillis.takeIf { it != base?.minPulseMillis },
                maxPulseMillis = value.maxPulseMillis.takeIf { it != base?.maxPulseMillis },
                minAngleRadians = value.minAngleRadians.takeIf { it != base?.minAngleRadians },
                minAxisDominance = value.minAxisDominance.takeIf { it != base?.minAxisDominance },
                minIntervalMillis = value.minIntervalMillis.takeIf { it != base?.minIntervalMillis },
                maxIntervalMillis = value.maxIntervalMillis.takeIf { it != base?.maxIntervalMillis },
                maxGestureMillis = value.maxGestureMillis.takeIf { it != base?.maxGestureMillis },
                refractoryMillis = value.refractoryMillis.takeIf { it != base?.refractoryMillis },
                maxSampleGapMillis = value.maxSampleGapMillis.takeIf { it != base?.maxSampleGapMillis },
                maxAccelerationDeviation = value.maxAccelerationDeviation.takeIf { it != base?.maxAccelerationDeviation },
            ).takeUnless { it == WearGesturePatch() }
    }
}
