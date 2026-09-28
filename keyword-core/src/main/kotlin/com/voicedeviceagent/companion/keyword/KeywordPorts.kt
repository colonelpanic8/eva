package com.voicedeviceagent.companion.keyword

/** Local stop provenance, independent of a transport or execution implementation. */
enum class StopSource { KEYWORD, NOTIFICATION, UI, HOST }

/** Calls must synchronously latch or clear the coordinator's stop, without network work. */
interface StopLatch {
    fun stop(source: StopSource)

    fun resume()
}

interface AudioTransmissionGate {
    /** Close atomically with frame submission; after setAudioAllowed(false) returns, queued and new sends must be blocked. */
    fun setAudioAllowed(allowed: Boolean)
}

/** Enqueue attachment changes without blocking or reentering the state machine. */
interface VoiceSessionControl {
    fun wake()

    fun sleep()
}

enum class VoiceNotice { Mute, Unmute, Sleep, Wake, Stop, Resume }

fun interface KeywordNotices {
    /** Enqueue a notice without waiting for delivery. */
    fun notify(message: VoiceNotice)
}
