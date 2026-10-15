package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.call.CallNotPlaced
import java.util.concurrent.atomic.AtomicReference

/**
 * All routes share a single device audio/floor owner, including SDK calls that Android Telecom
 * cannot see. Reserve before the first suspending dial step: checking an SDK's busy flag leaves
 * a race while connect is still running. The reservation lasts through audio cleanup, not just dial.
 */
internal class CallAdmission {
    private val current = AtomicReference<Lease?>()

    suspend fun acquire(): Lease {
        while (true) {
            val previous = current.get()
            if (previous != null) {
                // Losing the observer must not let the next task inject audio into a call left alive.
                // A later attempt can recover after the helper returns and positively observes its end.
                val uncertain = previous.uncertain
                if (uncertain == null || runCatching { uncertain.confirmEnded() }.getOrDefault(false) != true) {
                    throw CallNotPlaced(
                        if (uncertain == null) {
                            "Another call is already in progress. End it before EVA places a new one."
                        } else {
                            "EVA could not confirm the previous call ended. ${uncertain.lostCheck} Restore call observation before starting another call."
                        },
                    )
                }
                current.compareAndSet(previous, null)
                continue
            }
            val lease = Lease()
            if (current.compareAndSet(null, lease)) return lease
        }
    }

    inner class Lease {
        @Volatile internal var uncertain: CallRoute? = null

        fun release() {
            current.compareAndSet(this, null)
        }

        fun observationLost(route: CallRoute) {
            uncertain = route
        }
    }
}
