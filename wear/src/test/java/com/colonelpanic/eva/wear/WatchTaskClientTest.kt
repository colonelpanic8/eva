package com.colonelpanic.eva.wear

import android.app.Application
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class WatchTaskClientTest {
    @Test fun `dictation carries its original conversation and question while new requests remain independent`() =
        runTest {
            val sent = mutableListOf<WatchTaskRequest>()
            val client =
                WatchTaskClient(RuntimeEnvironment.getApplication(), backgroundScope, { "phone" }) { _, request -> sent += request }
            client.submit("Paris", "conversation", "question")
            client.submit("Another task", null, null)
            runCurrent()
            assertEquals("conversation", sent[0].threadId)
            assertEquals("question", sent[0].questionId)
            assertNull(sent[1].threadId)
            client.receive("stranger", WatchTaskReply(sent[1].id, "accepted", "Accepted", "wrong").chunks().single())
            assertNull(client.state.value.threadId)
            client.receive("phone", WatchTaskReply(sent[1].id, "accepted", "Accepted", "new-conversation").chunks().single())
            runCurrent()
            assertEquals("new-conversation", client.state.value.threadId)
            client.receive("phone", WatchTaskReply(sent[0].id, "accepted", "Answer accepted", "conversation").chunks().single())
            assertEquals("new-conversation", client.state.value.threadId)
        }

    @Test fun `an old read cannot replace the conversation reopened after navigation`() =
        runTest {
            val sent = mutableListOf<WatchTaskRequest>()
            val client =
                WatchTaskClient(RuntimeEnvironment.getApplication(), backgroundScope, { "phone" }) { _, request ->
                    sent += request
                }
            client.open("first")
            runCurrent()
            val old = sent.last()
            client.open("second")
            runCurrent()
            client.open("first")
            runCurrent()
            val newest = sent.last()
            client.receive(
                "phone",
                WatchTaskReply(
                    newest.id,
                    "read",
                    "working",
                    "first",
                    entries = listOf(WatchConversationEntry("new", "EVA", "New result")),
                ).chunks().single(),
            )
            client.receive(
                "phone",
                WatchTaskReply(
                    old.id,
                    "read",
                    "working",
                    "first",
                    entries = listOf(WatchConversationEntry("old", "EVA", "Stale result")),
                ).chunks().single(),
            )
            assertEquals(
                listOf("New result"),
                client.state.value.entries
                    .map { it.text },
            )
        }

    @Test fun `missing acknowledgement never retries a mutation and later confirmation remains usable`() =
        runTest {
            val sent = mutableListOf<WatchTaskRequest>()
            val client =
                WatchTaskClient(RuntimeEnvironment.getApplication(), backgroundScope, { "phone" }) { _, request -> sent += request }
            client.submit("Do this", null, null)
            runCurrent()
            advanceTimeBy(31_000)
            runCurrent()
            assertEquals(1, sent.size)
            assertTrue(
                client.state.value.receipt
                    .contains("unconfirmed"),
            )
            client.receive("phone", WatchTaskReply(sent.single().id, "accepted", "Accepted on phone", "conversation").chunks().single())
            assertEquals("conversation", client.state.value.threadId)
            assertEquals("Accepted on phone", client.state.value.receipt)
        }
}
