package com.colonelpanic.eva.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchTasksTest {
    @Test fun `large unicode answers survive fragmented out of order and duplicate delivery`() {
        val reply =
            WatchTaskReply("id", "read", "answered", entries = listOf(WatchConversationEntry("entry", "EVA", "こんにちは 🌍".repeat(20_000))))
        val chunks = reply.chunks()
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { WearProtocol.encode(it).size < 100_000 })
        val assembly = WatchReplyAssembly()
        assertNull(assembly.accept(chunks.last()))
        assertNull(assembly.accept(chunks.last()))
        var result: WatchTaskReply? = null
        for (chunk in chunks.dropLast(1).reversed()) result = assembly.accept(chunk)
        assertEquals(reply, result)
    }
}
