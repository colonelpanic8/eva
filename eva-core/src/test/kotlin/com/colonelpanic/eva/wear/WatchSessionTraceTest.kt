package com.colonelpanic.eva.wear

import com.colonelpanic.eva.diagnostics.EvaTrace
import com.colonelpanic.eva.diagnostics.TraceEvent
import com.colonelpanic.eva.diagnostics.TraceSink
import com.colonelpanic.eva.providers.ProviderEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSessionTraceTest {
    @Test fun `false positive label distinguishes no speech early end and failed startup without recording words`() {
        val events = mutableListOf<TraceEvent>()
        val before = EvaTrace.sink
        EvaTrace.sink = TraceSink(events::add)
        try {
            fun close(
                id: String,
                length: Long,
                connected: Boolean = true,
                speech: Boolean = false,
            ) {
                val session = WatchSessionTrace(id, 1000)
                if (connected) session.event(ProviderEvent.Connected("session", "catalog"), 1000)
                if (speech) session.event(ProviderEvent.Transcript("user", "private speech content"), 1000)
                session.closed(1000 + length, "thread", "ended")
            }
            close("silent", 20_000)
            close("brief", 2000, speech = true)
            close("normal", 20_000, speech = true)
            close("failed", 1000, connected = false)
            val closed = events.filter { it.name == "wear.session.closed" }.associateBy { it.fields["request"] }
            assertEquals("true", closed.getValue("silent").fields["likelyFalsePositive"])
            assertEquals("false", closed.getValue("silent").fields["endedImmediately"])
            assertEquals("true", closed.getValue("brief").fields["endedImmediately"])
            assertEquals("false", closed.getValue("normal").fields["likelyFalsePositive"])
            assertEquals("false", closed.getValue("failed").fields["likelyFalsePositive"])
            assertFalse(events.any { it.line().contains("private speech content") })
            assertEquals(3, events.count { it.name == "wear.session.connected" })
        } finally {
            EvaTrace.sink = before
        }
    }

    @Test fun `early hangup is timed from connection rather than a slow startup`() {
        val events = mutableListOf<TraceEvent>()
        val before = EvaTrace.sink
        EvaTrace.sink = TraceSink(events::add)
        try {
            val session = WatchSessionTrace("slow", 1000)
            session.event(ProviderEvent.Connected("session", "catalog"), 20_000)
            session.event(ProviderEvent.UserSpeaking, 20_500)
            session.closed(21_000, "thread", "ended")
            assertEquals("1000", events.last().fields["durationMillis"])
            assertEquals("19000", events.last().fields["startupMillis"])
            assertEquals("true", events.last().fields["endedImmediately"])
        } finally {
            EvaTrace.sink = before
        }
    }

    @Test fun `watch start failure gives specific setup instructions`() {
        assertTrue(watchStartFailure(true, false, true, false)!!.contains("default assistant"))
        assertTrue(watchStartFailure(true, true, false, false)!!.contains("microphone"))
        assertTrue(watchStartFailure(false, true, true, false)!!.contains("disabled"))
        assertTrue(watchStartFailure(true, true, true, true)!!.contains("already running"))
        assertEquals(null, watchStartFailure(true, true, true, false))
    }
}
