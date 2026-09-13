package com.colonelpanic.eva.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalCommandProviderTest {
    private val provider = LocalCommandProvider()

    @Test
    fun `only explicit map commands produce proposals`() {
        assertEquals("Golden Gate Park", provider.propose("one", "map Golden Gate Park")?.arguments?.get("destination"))
        assertEquals("1 Ferry Building", provider.propose("two", " OPEN MAPS TO 1 Ferry Building ")?.arguments?.get("destination"))
        assertNull(provider.propose("three", "send a message"))
        assertNull(provider.propose("four", "mapping"))
    }

    @Test
    fun `preserves call identity and leaves validation to dispatcher`() {
        val proposal = checkNotNull(provider.propose("stable:id", "map"))
        assertEquals("stable:id", proposal.callId)
        assertEquals("", proposal.arguments["destination"])
    }
}
