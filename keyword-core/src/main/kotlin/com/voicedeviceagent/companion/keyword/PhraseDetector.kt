package com.voicedeviceagent.companion.keyword

/**
 * How one phrase model's scores become detections. A phrase fires when [patience] consecutive
 * 80 ms scores reach [threshold], and then not again until the score has dropped below the
 * threshold and [refractoryMs] has passed. With [burstGate] set, a detection also needs that much
 * speech energy just before it; the gate can veto the model but never fires on its own.
 */
data class PhraseSpec(
    val id: String,
    val threshold: Float = 0.5f,
    val refractoryMs: Long = 2_000,
    val patience: Int = 1,
    val burstGate: SpeechBurstGate? = null,
) {
    init {
        require(threshold > 0f && threshold < 1f) { "threshold must be in (0, 1)" }
        require(refractoryMs >= 0 && patience >= 1)
    }
}

class PhraseDetector(
    val spec: PhraseSpec,
) {
    private var consecutive = 0
    private var armed = true
    private var lastFiredNanos: Long? = null

    /** Feeds one score at [atNanos]; true when this score is a detection. */
    fun offer(
        score: Float,
        atNanos: Long,
    ): Boolean {
        if (!score.isFinite() || score < spec.threshold) {
            consecutive = 0
            armed = true
            return false
        }
        consecutive++
        if (!armed || consecutive < spec.patience) return false
        val last = lastFiredNanos
        if (last != null && atNanos - last < spec.refractoryMs * 1_000_000) return false
        armed = false
        lastFiredNanos = atNanos
        return true
    }
}
