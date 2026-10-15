package com.colonelpanic.eva.wear

import android.app.Application
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.conversation.MemoryConversationStore
import com.colonelpanic.eva.conversation.ThreadController
import com.colonelpanic.eva.conversation.TurnStatus
import com.colonelpanic.eva.providers.ConversationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class PhoneWatchTasksTest {
    @Test fun `lost acknowledgements and listener recreation never submit the same request twice`() =
        runTest {
            val store = MemoryConversationStore()
            val registry = CapabilityRegistry(emptyMap(), emptyList())
            val repository = MemoryInvocationRepository()
            val gate = CompletableDeferred<Unit>()
            val controller =
                ThreadController(
                    registry,
                    CapabilityDispatcher(registry, repository),
                    repository,
                    store,
                    backgroundScope,
                    providerFactory = {
                        object : ConversationProvider {
                            override suspend fun open(
                                request: com.colonelpanic.eva.providers.SessionOpenRequest,
                            ): com.colonelpanic.eva.providers.ConversationSession {
                                gate.await()
                                error("closed")
                            }
                        }
                    },
                )
            runCurrent()
            val context = RuntimeEnvironment.getApplication()
            val first = PhoneWatchTasks(context, controller, store) { _, _ -> throw IllegalStateException("watch disconnected") }
            val request = WatchTaskRequest(UUID.randomUUID().toString(), WatchTaskOperation.SUBMIT, "Do this once")
            first.message("watch", WearProtocol.encode(request))
            runCurrent()
            val replies = mutableListOf<WatchTaskReply>()
            val restored = PhoneWatchTasks(context, controller, store) { _, chunk -> WatchReplyAssembly().accept(chunk)?.let(replies::add) }
            restored.message("watch", WearProtocol.encode(request))
            assertEquals(1, store.threads().size)
            assertEquals(1, store.turns(store.threads().single().id).size)
            assertEquals("accepted", replies.single().status)
            restored.message("watch", WearProtocol.encode(request.copy(text = "Different request")))
            assertEquals("failed", replies.last().status)
            assertEquals(1, store.threads().size)
            controller.stopAllTasks()
            runCurrent()
        }

    @Test fun `two watch requests are independent and stop only affects its named conversation`() =
        runTest {
            val store = MemoryConversationStore()
            val registry = CapabilityRegistry(emptyMap(), emptyList())
            val repository = MemoryInvocationRepository()
            val gate = CompletableDeferred<Unit>()
            val controller =
                ThreadController(
                    registry,
                    CapabilityDispatcher(registry, repository),
                    repository,
                    store,
                    backgroundScope,
                    providerFactory = {
                        object : ConversationProvider {
                            override suspend fun open(
                                request: com.colonelpanic.eva.providers.SessionOpenRequest,
                            ): com.colonelpanic.eva.providers.ConversationSession {
                                gate.await()
                                error("closed")
                            }
                        }
                    },
                )
            runCurrent()
            val replies = mutableListOf<WatchTaskReply>()
            val phone =
                PhoneWatchTasks(
                    RuntimeEnvironment.getApplication(),
                    controller,
                    store,
                ) { _, chunk -> WatchReplyAssembly().accept(chunk)?.let(replies::add) }
            repeat(2) {
                phone.message(
                    "watch",
                    WearProtocol.encode(WatchTaskRequest(UUID.randomUUID().toString(), WatchTaskOperation.SUBMIT, "Task $it")),
                )
            }
            runCurrent()
            val a = replies[0]
            val b = replies[1]
            assertNotEquals(a.threadId, b.threadId)
            phone.message(
                "watch",
                WearProtocol.encode(WatchTaskRequest(UUID.randomUUID().toString(), WatchTaskOperation.STOP, threadId = a.threadId)),
            )
            runCurrent()
            assertEquals(TurnStatus.INTERRUPTED, store.turns(a.threadId!!).single().status)
            assertEquals(TurnStatus.OPEN, store.turns(b.threadId!!).single().status)
            phone.message("watch", WearProtocol.encode(WatchTaskRequest(UUID.randomUUID().toString(), WatchTaskOperation.LIST)))
            assertEquals(2, replies.last().conversations.size)
            assertTrue(replies.last().conversations.any { it.id == a.threadId && it.status == "interrupted" })
            controller.stopAllTasks()
            runCurrent()
        }
}
