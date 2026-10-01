package com.colonelpanic.eva.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderEventLogTest {
    private fun event(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `wire events keep identities status and text but never audio or session bodies`() {
        val log = ProviderEventLog(clock = { 5 })
        log.record("c", false, event("""{"type":"response.output_audio.delta","response_id":"r1","item_id":"i1","delta":"QUJDREVGRw=="}"""))
        log.record(
            "c",
            false,
            event(
                """{"type":"session.updated","session":{"id":"s1","instructions":"Long prompt","tools":[{"name":"a"},{"name":"b"}]}}""",
            ),
        )
        log.record(
            "c",
            false,
            event(
                """{"type":"response.done","response":{"id":"r1","status":"cancelled","status_details":{"reason":"turn_detected"},""" +
                    """"metadata":{"request":"input-7"}}}""",
            ),
        )
        log.record(
            "c",
            false,
            event("""{"type":"response.function_call_arguments.done","call_id":"call-1","arguments":"{\"room\":\"den\"}"}"""),
        )

        val (audio, session, done, call) = log.snapshot()
        assertNull(audio.text)
        assertEquals("r1", audio.ids["response_id"])
        assertEquals(mapOf("session.id" to "s1", "session.tools" to "2"), session.ids)
        assertNull(session.text)
        assertEquals("cancelled (turn_detected)", done.status)
        assertEquals("input-7", done.ids["metadata.request"])
        assertEquals("{\"room\":\"den\"}", call.text)
    }

    @Test
    fun `consecutive transcript deltas for one item coalesce and the ring stays bounded`() {
        var now = 0L
        val log = ProviderEventLog(capacity = 3, clock = { ++now })
        listOf("Hel", "lo").forEach {
            log.record("c", false, event("""{"type":"response.output_audio_transcript.delta","item_id":"i1","delta":"$it"}"""))
        }
        val coalesced = log.snapshot().single()
        assertEquals("Hello", coalesced.text)
        assertEquals(2, coalesced.count)
        assertEquals(1L to 2L, coalesced.atMillis to coalesced.lastAtMillis)

        (1..5).forEach { log.record("c", true, event("""{"type":"response.cancel","response_id":"r$it"}""")) }
        assertEquals(listOf("r3", "r4", "r5"), log.snapshot().map { it.ids["response_id"] })
    }

    @Test
    fun `a very large outbound event keeps only its type without being parsed`() {
        val log = ProviderEventLog()
        var parsed = false
        log.recordOutbound("c", """{"type":"session.update","session":{"instructions":"${"x".repeat(70_000)}"}}""") {
            parsed = true
            JsonObject(emptyMap())
        }
        assertEquals(false, parsed)
        assertEquals("session.update", log.snapshot().single().type)
    }
}
