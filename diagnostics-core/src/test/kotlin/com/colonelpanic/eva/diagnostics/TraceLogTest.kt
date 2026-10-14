package com.colonelpanic.eva.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TraceLogTest {
    private val directory = Files.createTempDirectory("trace").toFile()

    @After
    fun cleanUp() {
        directory.deleteRecursively()
        EvaTrace.sink = null
        EvaTrace.verbose = false
    }

    private fun event(index: Int) = TraceEvent(index.toLong(), TraceLevel.INFO, "event", mapOf("index" to index.toString()))

    @Test
    fun `the ring keeps only the newest events in memory and on disk`() {
        val log = TraceLog(capacity = 10, directory = directory)
        (1..25).forEach { log.record(event(it)) }

        assertEquals((16..25).map(Int::toString), log.snapshot().map { it.fields.getValue("index") })
        val lines = directory.listFiles()!!.sumOf { it.readLines().size }
        assertTrue("disk holds $lines lines", lines <= 20)
    }

    @Test
    fun `events survive a restart`() {
        TraceLog(capacity = 10, directory = directory).apply { (1..13).forEach { record(event(it)) } }

        val restored = TraceLog(capacity = 10, directory = directory)
        restored.record(event(14))
        assertEquals((5..14).map(Int::toString), restored.snapshot().map { it.fields.getValue("index") })
    }

    @Test
    fun `a corrupt line is skipped rather than losing the rest`() {
        File(directory, TraceLog.CURRENT).writeText(event(1).toJson().toString() + "\nnot json\n" + event(2).toJson().toString() + "\n")
        assertEquals(listOf(1L, 2L), TraceLog(capacity = 10, directory = directory).snapshot().map { it.atMillis })
    }

    @Test
    fun `verbose events are dropped unless verbose logging is on and secrets are scrubbed`() {
        val log = TraceLog(capacity = 10)
        EvaTrace.sink = log
        EvaTrace.verbose("speech.started")
        EvaTrace.info("provider.failure", "message" to "Authorization: Bearer abcdefghijkl", "absent" to null)
        EvaTrace.verbose = true
        EvaTrace.verbose("speech.started")

        assertEquals(listOf("provider.failure", "speech.started"), log.snapshot().map { it.name })
        val fields = log.snapshot().first().fields
        assertFalse(fields.getValue("message").contains("abcdefghijkl"))
        assertFalse("absent" in fields)
    }
}
