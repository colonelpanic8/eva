package com.colonelpanic.eva.wear

import com.colonelpanic.eva.diagnostics.EvaTrace
import com.colonelpanic.eva.diagnostics.TraceSink
import org.junit.Assert.assertEquals
import org.junit.Test

class WearGestureProtocolTest {
    @Test fun `data layer snapshots preserve a nondefault pulse count and older snapshots use four`() {
        val snapshot = WatchConfiguration(42, WearGestureConfiguration(requiredPulses = 6, maxGestureMillis = 1600))
        assertEquals(snapshot, WearProtocol.decode<WatchConfiguration>(WearProtocol.encode(snapshot)))
        val old = WearProtocol.encode(snapshot).toString(Charsets.UTF_8).replace("\"requiredPulses\":6,", "")
        assertEquals(4, WearProtocol.decode<WatchConfiguration>(old.toByteArray()).value.requiredPulses)
    }

    @Test fun `near miss transport and lifecycle export retain the accepted count and every interval`() {
        val pulses = (0..2).map { RotationPulseFeatures(100L + it * 220, 80, if (it % 2 == 0) 1 else -1, 8.0, 0.5, 0.99, 0.3) }
        val event = WatchGestureEvent(false, "next_pulse_missing (3/4 valid pulses)", 1000, 1, pulses, null, 4)
        val record = WatchTraceRecord("id", 2000, event)
        val decoded = WearProtocol.decode<WatchTraceRecord>(WearProtocol.encode(record))
        assertEquals(record, decoded)
        val before = EvaTrace.sink
        try {
            val events = mutableListOf<com.colonelpanic.eva.diagnostics.TraceEvent>()
            EvaTrace.sink = TraceSink(events::add)
            traceGesture(decoded)
            val fields = events.single().fields
            assertEquals("3", fields["validPulseCount"])
            assertEquals("4", fields["requiredPulses"])
            assertEquals("220", fields["interval2Millis"])
            assertEquals("8.0", fields["pulse3.peakRate"])
        } finally {
            EvaTrace.sink = before
        }
    }
}
