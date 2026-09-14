package com.colonelpanic.eva.ui

import com.colonelpanic.eva.conversation.ConversationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectBarTest {
    private val ready = ConversationState(isLoading = false)

    @Test
    fun `credentials are required before either way in is offered`() {
        assertTrue(connectPrompt(ready, hasCredential = true).canConnect)
        val missing = connectPrompt(ready, hasCredential = false)
        assertFalse(missing.canConnect)
        assertEquals("Add an account, API key, or paired host in Settings.", missing.supporting)
    }

    @Test
    fun `an unusable conversation never blames missing credentials`() {
        val loading = connectPrompt(ready.copy(isLoading = true), hasCredential = false)
        assertFalse(loading.canConnect)
        assertNull(loading.supporting)

        val broken = connectPrompt(ready.copy(errorMessage = "History is unavailable."), hasCredential = true)
        assertFalse(broken.canConnect)
        assertNull(broken.supporting)
    }
}
