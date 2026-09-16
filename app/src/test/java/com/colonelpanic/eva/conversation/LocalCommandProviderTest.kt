package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.capability.CapabilityRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalCommandProviderTest {
    private val provider = LocalCommandProvider()

    @Test
    fun `only explicit text commands produce proposals`() {
        val proposal = checkNotNull(provider.propose("one", " TEXT +1 (202) 555-0100: Meet at 5 ", "test-revision"))
        assertEquals(CapabilityRegistry.SMS_COMPOSE, proposal.capabilityId)
        assertEquals(mapOf("recipient" to "+1 (202) 555-0100", "message" to "Meet at 5"), proposal.arguments)
        assertNull(provider.propose("two", "send a message", "test-revision"))
        assertNull(provider.propose("three", "map Golden Gate Park", "test-revision"))
        assertNull(provider.propose("four", "texting", "test-revision"))
    }

    @Test
    fun `preserves call identity and leaves validation to dispatcher`() {
        val proposal = checkNotNull(provider.propose("stable:id", "text :", "test-revision"))
        assertEquals("stable:id", proposal.callId)
        assertEquals("test-revision", proposal.catalogRevision)
        assertEquals("", proposal.arguments["recipient"])
        assertEquals("", proposal.arguments["message"])
    }
}
