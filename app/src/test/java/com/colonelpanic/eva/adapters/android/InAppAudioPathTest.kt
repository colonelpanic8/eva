package com.colonelpanic.eva.adapters.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InAppAudioPathTest {
    private class Port : SdkAudioPort {
        var rendering = false
        var pulls = 0

        override fun pull(chunk: ByteBuffer): Boolean {
            if (!rendering) return false
            pulls++
            chunk.order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until chunk.capacity() / 2) chunk.putShort(2 * i, 1_000)
            return true
        }

        override fun push(chunk: ByteBuffer) = true
    }

    private class Loud : OwnerMicrophone {
        override fun read(samples: ShortArray) = samples.fill(Short.MAX_VALUE)

        override fun release() = Unit
    }

    @Test fun readsTheOtherPartyAtRealTimeInTenMillisecondChunksWithSilenceUntilTheSdkRenders() {
        val port = Port()
        var now = 0L
        val sleeps = mutableListOf<Long>()
        val path = InAppAudioPath(port, ::Loud, nanoTime = { now }, sleep = { sleeps += it })
        path.open()
        val frame = ByteArray(960) { 7 }
        assertTrue(path.readRemote(frame))
        assertEquals(listOf(20L), sleeps)
        assertTrue(frame.all { it == 0.toByte() })

        now = 50_000_000
        port.rendering = true
        assertTrue(path.readRemote(frame))
        assertEquals("A late reader catches up instead of sleeping.", listOf(20L), sleeps)
        assertEquals(2, port.pulls)
        assertEquals(1_000.toShort(), ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getShort(958))

        path.close()
        assertFalse(path.readRemote(frame))
        assertFalse(path.writeUplink(frame))
    }

    @Test fun theOwnersMicrophoneIsMixedIntoTheAgentsUplinkWithoutWrapping() {
        val pushed = mutableListOf<Short>()
        val port =
            object : SdkAudioPort {
                override fun pull(chunk: ByteBuffer) = false

                override fun push(chunk: ByteBuffer): Boolean {
                    chunk.order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until chunk.capacity() / 2) pushed += chunk.getShort(2 * i)
                    return true
                }
            }
        val path = InAppAudioPath(port, ::Loud)
        val agent =
            ByteBuffer
                .allocate(960)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply { repeat(480) { putShort(10_000) } }
                .array()
        path.routeOwnerMicrophone(true)
        assertTrue(path.writeUplink(agent))
        assertEquals(List(480) { Short.MAX_VALUE }, pushed)
    }
}
