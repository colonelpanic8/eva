package com.colonelpanic.eva

/**
 * Android kills the process when `startForegroundService()` is not answered by
 * `startForeground()`. Work that finishes in milliseconds asks to stop before the service's
 * first `onStartCommand` has run, and stopping it that early is what trips the kill, so a stop
 * arriving before the service is foreground is held here and applied by the service itself once
 * it has gone foreground.
 */
class ForegroundServiceGate {
    private var foreground = false
    private var stopWanted = false

    @Synchronized
    fun starting() {
        stopWanted = false
    }

    /** True when `stopService` is safe now; false leaves the stop for [foregrounded] to apply. */
    @Synchronized
    fun stopping(): Boolean {
        stopWanted = true
        return foreground
    }

    /** Called right after `startForeground`; true when a stop arrived before the service existed. */
    @Synchronized
    fun foregrounded(): Boolean {
        foreground = true
        return stopWanted
    }

    @Synchronized
    fun destroyed() {
        foreground = false
        stopWanted = false
    }
}
