package com.colonelpanic.eva.adapters.android

import android.media.AudioManager
import android.telecom.Call
import android.telecom.DisconnectCause
import com.colonelpanic.eva.call.AnsweredEvidence
import com.colonelpanic.eva.call.EndCause
import com.colonelpanic.eva.call.LegObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppCallProviderTest {
    private fun signals(
        telecom: TelecomCallState?,
        millis: Long = 0,
    ) = CallSignals(AudioManager.MODE_IN_COMMUNICATION, emptyList(), telecom, millis)

    private fun call(
        state: Int,
        cause: Int = DisconnectCause.UNKNOWN,
        removed: Boolean = false,
    ) = TelecomCallState(state, cause, "cause $cause", removed)

    @Test fun followsWhatsAppsTelecomCallFromDialingToItsDisconnectCause() {
        val observer = WhatsAppTransport.telecomObserver()
        assertEquals(LegObservation.Pending, observer.observe(signals(null)))
        assertEquals(LegObservation.Live(null), observer.observe(signals(call(Call.STATE_DIALING))))
        assertEquals(LegObservation.Live(AnsweredEvidence.PROVIDER_ASSERTION), observer.observe(signals(call(Call.STATE_ACTIVE))))
        assertEquals(
            LegObservation.Gone(EndCause.REMOTE_HANGUP, "Telecom disconnect: cause ${DisconnectCause.REMOTE}"),
            observer.observe(signals(call(Call.STATE_DISCONNECTED, DisconnectCause.REMOTE))),
        )
        assertEquals(EndCause.BUSY, CallEngine.telecomEndCause(DisconnectCause.REJECTED))
        assertEquals(EndCause.NO_ANSWER, CallEngine.telecomEndCause(DisconnectCause.MISSED))
        assertNull(CallEngine.telecomEndCause(DisconnectCause.OTHER))
    }

    @Test fun aCallThatLeavesTelecomOrNeverAppearsEndsWithAReason() {
        val observer = WhatsAppTransport.telecomObserver()
        val removed = observer.observe(signals(call(Call.STATE_ACTIVE, removed = true)))
        assertEquals(null, (removed as LegObservation.Gone).cause)
        val missing = observer.observe(signals(null, WhatsAppTransport.APPEAR_MILLIS))
        assertEquals(EndCause.FAILED, (missing as LegObservation.Gone).cause)
        assertTrue(missing.detail.contains("check WhatsApp on the phone"))
    }

    @Test fun readsThePhoneNumberOutOfWhatsAppsAccountId() {
        assertEquals("+15592493930", WhatsAppTransport.accountNumber("15592493930@s.whatsapp.net"))
        assertNull(WhatsAppTransport.accountNumber("120363025@g.us"))
        assertNull(WhatsAppTransport.accountNumber("status@broadcast"))
        assertNull(WhatsAppTransport.accountNumber(null))
    }
}
