package com.colonelpanic.eva.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class BrokerEndpointTest {
    private val code = "a".repeat(48)

    @Test
    fun `connection code is separate from the socket URL`() {
        val endpoint = BrokerEndpoint.parse("http://localhost:54321/#$code")
        assertEquals("ws://localhost:54321/device", endpoint.socketUrl)
        assertEquals(code, endpoint.accessCode)
    }

    @Test
    fun `reject remote cleartext and never include access code in parse errors`() {
        for (link in listOf("http://example.com:54321/#$code", "http://bad host:54321/#$code")) {
            val error = assertThrows(IllegalArgumentException::class.java) { BrokerEndpoint.parse(link) }
            assertFalse(error.message.orEmpty().contains(code))
        }
    }
}
