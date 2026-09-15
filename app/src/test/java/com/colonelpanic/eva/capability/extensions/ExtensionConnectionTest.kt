package com.colonelpanic.eva.capability.extensions

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal val extensionIdentity = ExtensionIdentity(0, "example.app", "example.app/.Extension", "abc", 10001, 1)
internal val extensionSchema =
    Json
        .parseToJsonElement(
            """{"type":"object","properties":{},"required":[],"additionalProperties":false}""",
        ).jsonObject
internal val extensionCapability = Capability("read", "Read", "Read data", extensionSchema, Effect.READ, 1000, 16384)
internal val extensionDescription =
    """
    {
    "protocolVersion":1,"status":"completed","reasonCode":null,"truncated":false,"content":[],
    "descriptor":{"protocolVersion":1,"descriptorRevision":"v1","authorizationScopeRevision":"account1",
    "title":"Example","capabilities":[{
    "tool":{"name":"read","title":"Read","description":"Read data","inputSchema":$extensionSchema},"effects":"read",
    "execution":{"mode":"synchronous","requiresForeground":false,"maxWaitMillis":1000},
    "result":{"maxBytes":16384}}]}}
    """.trimIndent()

internal class FakeExtensionConnector :
    ExtensionConnector,
    ExtensionConnection {
    var binds = 0
    var closes = 0
    var submits = 0
    var bindDelay = 0L
    var bindFailure = false
    var reply: String? = null
    lateinit var died: () -> Unit
    lateinit var callback: (Int, String, String) -> Unit
    lateinit var id: String
    var deadline = 0L
    var revision: String? = null

    override suspend fun connect(
        identity: ExtensionIdentity,
        died: () -> Unit,
    ): ExtensionConnection {
        binds++
        this.died = died
        delay(bindDelay)
        check(!bindFailure)
        return this
    }

    var request: String? = null

    override fun describe(
        id: String,
        request: String,
        deadline: Long,
        callback: (Int, String, String) -> Unit,
    ) {
        submits++
        this.request = request
        this.id = id
        this.deadline = deadline
        this.callback = callback
        reply?.let { callback(extensionIdentity.uid, id, it) }
    }

    override fun execute(
        id: String,
        revision: String,
        capability: String,
        arguments: String,
        deadline: Long,
        callback: (Int, String, String) -> Unit,
    ) {
        this.revision = revision
        describe(id, "", deadline, callback)
    }

    override fun close() {
        closes++
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExtensionConnectionTest {
    @Test
    fun `only matching UID and outstanding request can complete once`() =
        runTest {
            val fake = FakeExtensionConnector()
            val manager = ExtensionConnectionManager(fake) { testScheduler.currentTime }
            val pending = async { manager.describe(extensionIdentity) }
            runCurrent()
            fake.callback(123, fake.id, "wrong UID")
            fake.callback(extensionIdentity.uid, "other", "wrong ID")
            runCurrent()
            assertFalse(pending.isCompleted)
            assertEquals(5000, fake.deadline)
            fake.callback(extensionIdentity.uid, fake.id, "first")
            fake.callback(extensionIdentity.uid, fake.id, "duplicate")
            assertEquals(ExtensionExchange.Reply("first"), pending.await())
            assertEquals(1, fake.closes)
        }

    @Test
    fun `binding timeout is not submitted and execute timeout is uncertain without retry`() =
        runTest {
            val fake = FakeExtensionConnector().apply { bindDelay = 6000 }
            val manager = ExtensionConnectionManager(fake) { testScheduler.currentTime }
            assertTrue(manager.describe(extensionIdentity) is ExtensionExchange.Unavailable)
            assertEquals(0, fake.submits)
            fake.bindDelay = 100
            val pending = async { manager.execute(extensionIdentity, "call", "v1", extensionCapability, "{}") }
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(6000, fake.deadline)
            assertEquals(ExtensionExchange.Uncertain, pending.await())
            fake.callback(extensionIdentity.uid, "call", "late")
            assertEquals(2, fake.binds)
            assertEquals(1, fake.submits)
        }

    @Test
    fun `binder death and oversized response after submission are uncertain`() =
        runTest {
            val fake = FakeExtensionConnector()
            val manager = ExtensionConnectionManager(fake) { testScheduler.currentTime }
            val pending = async { manager.execute(extensionIdentity, "call", "v1", extensionCapability, "{}") }
            runCurrent()
            fake.died()
            assertEquals(ExtensionExchange.Uncertain, pending.await())
            fake.reply = "é".repeat(9000)
            assertEquals(ExtensionExchange.Uncertain, manager.execute(extensionIdentity, "next", "v1", extensionCapability, "{}"))
        }

    @Test
    fun `concurrency is bounded per package and cancellation releases it`() =
        runTest {
            val fake = FakeExtensionConnector()
            val manager = ExtensionConnectionManager(fake) { testScheduler.currentTime }
            val pending = async { manager.describe(extensionIdentity) }
            runCurrent()
            assertTrue(manager.describe(extensionIdentity) is ExtensionExchange.Unavailable)
            assertEquals(1, fake.binds)
            pending.cancel()
            pending.join()
            fake.reply = "ok"
            assertEquals(ExtensionExchange.Reply("ok"), manager.describe(extensionIdentity))
            assertEquals(ExtensionProtocol.describeRequest(), fake.request)
        }

    @Test
    fun `discovery rejects ambiguous packages including disabled second services`() {
        val valid = ExtensionCandidate(extensionIdentity, true, true, 1)
        assertEquals(extensionIdentity, selectExtensions(listOf(valid)).single().identity)
        assertEquals(null, selectExtensions(listOf(valid, valid.copy(enabled = false))).single().identity)
        assertEquals(null, selectExtensions(listOf(valid.copy(version = 2))).single().identity)
    }

    @Test
    fun `discovery debounces events preserves outage contracts and removes immediately`() =
        runTest {
            val fake = FakeExtensionConnector().apply { reply = extensionDescription }
            var scans = 0
            val discovery =
                ExtensionDiscovery(
                    {
                        scans++
                        listOf(ExtensionCandidate(extensionIdentity, true, true, 1))
                    },
                    ExtensionConnectionManager(fake) { testScheduler.currentTime },
                    backgroundScope,
                )
            repeat(10) { discovery.requestRefresh() }
            runCurrent()
            advanceTimeBy(251)
            runCurrent()
            assertEquals(1, scans)
            val descriptor =
                discovery.installed.value
                    .single()
                    .descriptor!!
            assertTrue(discovery.available(extensionIdentity, descriptor.digest))
            fake.bindFailure = true
            discovery.requestRefresh()
            runCurrent()
            advanceTimeBy(251)
            runCurrent()
            assertEquals(
                descriptor,
                discovery.installed.value
                    .single()
                    .descriptor,
            )
            assertFalse(discovery.available(extensionIdentity, descriptor.digest))
            discovery.invalidate(extensionIdentity.packageName, removed = true)
            assertTrue(discovery.installed.value.isEmpty())
        }
}
