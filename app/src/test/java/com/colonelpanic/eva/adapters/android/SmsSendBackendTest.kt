package com.colonelpanic.eva.adapters.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsSendBackendTest {
    @Test
    fun `unicode text that fits one part is not split`() {
        val message = "—".repeat(SmsParts.SINGLE_UNITS)
        assertEquals(listOf(message), SmsParts.unicode(message))
    }

    @Test
    fun `longer unicode text is split into concatenated parts that rejoin exactly`() {
        val message = "Hi Alex! I’m planning an interactive museum visit in Toyosu — what time would you like to go?"
        val parts = SmsParts.unicode(message)
        assertEquals(2, parts.size)
        assertEquals(SmsParts.CONCATENATED_UNITS, parts.first().length)
        assertEquals(message, parts.joinToString(""))
    }

    @Test
    fun `a part never ends between the halves of a surrogate pair`() {
        val message = "a".repeat(SmsParts.CONCATENATED_UNITS - 1) + "🙂".repeat(10)
        val parts = SmsParts.unicode(message)
        assertEquals(message, parts.joinToString(""))
        assertTrue(parts.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
        assertTrue(parts.all { it.length <= SmsParts.CONCATENATED_UNITS })
    }
}
