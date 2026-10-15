package com.colonelpanic.eva.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PcmPlaybackLedgerTest {
    @Test fun `interruption includes audio queued before the playback worker starts`() {
        val ledger = PcmPlaybackLedger(24000)
        ledger.enqueue("queued")
        assertEquals(listOf("queued"), ledger.pendingIds)
        assertEquals(mapOf("queued" to 0L), ledger.interrupt(0))
        assertEquals(emptyList<String>(), ledger.pendingIds)
    }

    @Test fun `completion waits for the playback head and retains position until the notification is consumed`() {
        val ledger = PcmPlaybackLedger(24000)
        ledger.enqueue("first")
        ledger.write("first", 4800, true)
        assertNull(ledger.finish("first", 2400))
        assertEquals(true, ledger.finish("first", 4800))
        ledger.enqueue("second")
        ledger.write("second", 2400, true)
        assertEquals(mapOf("first" to 200L, "second" to 50L), ledger.interrupt(6000))
    }

    @Test fun `muting any rendered portion prevents confirmation even after unmuting`() {
        val ledger = PcmPlaybackLedger(24000)
        ledger.enqueue("muted")
        ledger.write("muted", 2400, true)
        ledger.mute()
        ledger.write("muted", 2400, true)
        assertFalse(ledger.finish("muted", 4800)!!)
    }
}
