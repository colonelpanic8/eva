package com.colonelpanic.eva.adapters.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsPduTest {
    @Test
    fun `a group send request carries every recipient in the encapsulation byte order`() {
        val pdu = MmsPdu.sendRequest(listOf("+1 (555) 123-0000", "555-123-0001"), "Hi", "t1")
        assertEquals(
            "8C80987431008D92890181" +
                "972B31353535313233303030302F545950453D504C4D4E00" +
                "97353535313233303030312F545950453D504C4D4E00" +
                "84A3010402038381EA4869",
            pdu.hex(),
        )
    }

    @Test
    fun `a body longer than a single length byte is measured as a uintvar`() {
        val pdu = MmsPdu.sendRequest(listOf("5551230000"), "a".repeat(200), "t1")
        // One part, four header bytes, then 200 split across two seven-bit groups.
        assertTrue(pdu.hex().contains("01048148" + "03" + "8381EA" + "61".repeat(200)))
    }

    @Test
    fun `an empty recipient list is a programming error rather than an empty send`() {
        val failure = runCatching { MmsPdu.sendRequest(emptyList(), "Hi", "t1") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }
}
