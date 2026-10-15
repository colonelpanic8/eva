package com.colonelpanic.eva.adapters.android

import android.media.AudioManager
import com.colonelpanic.eva.call.AnsweredEvidence
import com.colonelpanic.eva.call.EndCause
import com.colonelpanic.eva.call.LegObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CellularCallProviderTest {
    @Test fun matchesTheDialedLineAcrossFormatsAndCountryCode() {
        assertTrue(CellularCallProvider.sameNumber("+15592493930", "5592493930"))
        assertTrue(CellularCallProvider.sameNumber("(559) 249-3930", "+1 559 249 3930"))
        assertFalse(CellularCallProvider.sameNumber("+15592493931", "5592493930"))
        assertFalse(CellularCallProvider.sameNumber(null, "5592493930"))
        assertFalse(CellularCallProvider.sameNumber("", "5592493930"))
    }

    private fun signals(
        mode: Int,
        vararg precise: PreciseCall,
    ) = CallSignals(mode, precise.toList(), telecom = null, millisSinceDial = 0)

    @Test fun preciseStateSaysWhenTheLineAnsweredAndTelephonyWhyItEnded() {
        val observer = CellularTransport.preciseStateObserver()
        assertEquals(LegObservation.Pending, observer.observe(signals(AudioManager.MODE_NORMAL)))
        assertEquals(LegObservation.Live(null), observer.observe(signals(AudioManager.MODE_IN_CALL, PreciseCall(3, -1))))
        assertEquals(
            LegObservation.Live(AnsweredEvidence.PRECISE_CALL_STATE),
            observer.observe(signals(AudioManager.MODE_IN_CALL, PreciseCall(1, -1))),
        )
        assertEquals(
            LegObservation.Gone(EndCause.REMOTE_HANGUP, "Android disconnect cause 2"),
            observer.observe(signals(AudioManager.MODE_NORMAL, PreciseCall(0, 2))),
        )
    }

    @Test fun withoutPreciseStateTheCallIsTakenAsAnsweredAndAnUnknownCauseIsLeftOpen() {
        val observer = CellularTransport.preciseStateObserver()
        assertEquals(LegObservation.Live(AnsweredEvidence.PROVIDER_ASSERTION), observer.observe(signals(AudioManager.MODE_IN_CALL)))
        assertEquals(LegObservation.Gone(null, "Android disconnect cause -1"), observer.observe(signals(AudioManager.MODE_NORMAL)))
    }
}
