package com.colonelpanic.eva.call

import kotlinx.serialization.Serializable

/**
 * How the call agent hears and is heard.
 *
 * [listenInByDefault] plays the agent's voice on the phone from the moment a call connects; the card
 * turns it on or off per call either way. It is off by default because listening in costs the other
 * party the ability to interrupt the agent: on the Pixel 11 Pro Fold the playback leaks into the
 * call's downlink capture and [CallEchoCanceller] removes only 0-21 dB of it, so the agent's input is
 * silenced while it may echo (see CallMonitorPlayback). Off, the agent hears everything and can be
 * interrupted at any time; the card still shows its words live. Configurations that never set the
 * field resolve to this default.
 *
 * Server VAD for the call agent's remote audio. Replaying a captured NIST time-line downlink at +12 dB,
 * the API defaults (0.5/500 ms) committed ten tick/noise segments in 85 s; 0.6/800 ms commit four and
 * keep the announcement one turn at both levels. A 0.6 threshold alone split it at mid-sentence pauses;
 * semantic_vad with low eagerness dropped it.
 */
@Serializable
data class CallAgentTuning(
    val vadThreshold: Double = 0.6,
    val vadPrefixPaddingMillis: Int = 300,
    val vadSilenceMillis: Int = 800,
    val listenInByDefault: Boolean = false,
    val whatsAppEnabled: Boolean = false,
) {
    init {
        require(vadThreshold in 0.0..1.0) { "Call agent VAD threshold must be between 0.0 and 1.0 (got $vadThreshold)." }
        require(vadPrefixPaddingMillis >= 0) {
            "Call agent VAD prefix padding must not be negative (got $vadPrefixPaddingMillis ms)."
        }
        require(vadSilenceMillis >= 0) { "Call agent VAD silence duration must not be negative (got $vadSilenceMillis ms)." }
    }
}
