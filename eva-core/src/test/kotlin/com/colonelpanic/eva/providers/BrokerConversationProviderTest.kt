package com.colonelpanic.eva.providers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerConversationProviderTest {
    @Test
    fun `a paired host refuses turn continuation`() =
        runTest {
            val endpoint = BrokerEndpoint.parse("http://localhost:54321/#" + "a".repeat(48))
            val error =
                runCatching {
                    BrokerConversationProvider(endpoint).open(
                        SessionOpenRequest(
                            instructions = "You are EVA.",
                            catalog = ProviderToolCatalog("rev-1", emptyList()),
                            continuation = Continuation("turn-1"),
                        ),
                    )
                }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertEquals("The paired host does not support continuing a turn.", error?.message)
        }
}
