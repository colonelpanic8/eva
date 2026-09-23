package com.colonelpanic.eva

/**
 * Android kills the process when `startForegroundService()` is not answered by
 * `startForeground()`. Work that finishes in milliseconds asks to stop before the service's
 * first `onStartCommand` has run, and stopping it that early is what trips the kill, so a stop
 * arriving before the service is foreground is held here and applied by the service itself once
 * it has gone foreground.
 */
class ForegroundServiceGate {
    private var pendingStarts = 0
    private var foreground = false
    private var stopWanted = false

    @Synchronized
    fun starting() {
        pendingStarts++
        stopWanted = false
    }

    fun requestStart(start: () -> Unit): Boolean {
        starting()
        return try {
            start()
            true
        } catch (_: SecurityException) {
            startRejected()
            false
        } catch (_: IllegalStateException) {
            startRejected()
            false
        }
    }

    @Synchronized
    fun startRejected() {
        if (pendingStarts > 0) pendingStarts--
    }

    /** True when `stopService` is safe now; false leaves the stop for [foregrounded] to apply. */
    @Synchronized
    fun stopping(): Boolean {
        stopWanted = true
        return foreground && pendingStarts == 0
    }

    /** Called right after `startForeground`; true when a stop arrived after the latest start. */
    @Synchronized
    fun foregrounded(): Boolean {
        if (pendingStarts > 0) pendingStarts--
        foreground = true
        return stopWanted && pendingStarts == 0
    }

    @Synchronized
    fun destroyed() {
        foreground = false
        if (pendingStarts == 0) stopWanted = false
    }
}
