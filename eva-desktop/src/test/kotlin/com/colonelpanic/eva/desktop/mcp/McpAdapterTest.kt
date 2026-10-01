package com.colonelpanic.eva.desktop.mcp

import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.capability.extensions.ExtensionGrants
import com.colonelpanic.eva.capability.extensions.ExtensionRuntime
import com.colonelpanic.eva.capability.extensions.MemoryGrantPersistence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpAdapterTest {
    private class FakeSession(
        val gate: CompletableDeferred<Unit>? = null,
    ) : McpSession {
        var description = "Move a window"
        override val serverName = "fake"
        override val serverVersion = "1.0"
        var running = true
        var onChanged: () -> Unit = {}
        var failure: Exception? = null
        val calls = mutableListOf<Pair<String, JsonObject>>()

        override fun alive() = running

        var gateOverride: CompletableDeferred<Unit>? = null

        override suspend fun tools(): List<McpToolListing> {
            (gateOverride ?: gate)?.await()
            gateOverride = null
            return listOf(
                McpToolListing(
                    "move_window",
                    "Move window",
                    description,
                    Json
                        .parseToJsonElement(
                            """{"type":"object","properties":{"x":{"type":"integer"},"title":{"type":["string","null"]}},"required":["x"]}""",
                        ).jsonObject,
                ),
            )
        }

        override suspend fun call(
            tool: String,
            arguments: JsonObject,
            timeoutMillis: Long,
        ): McpCallReply {
            failure?.let { throw it }
            calls += tool to arguments
            return McpCallReply(listOf("moved"), 0, null, isError = false)
        }

        override fun close() {
            running = false
        }
    }

    private val config = McpServerConfig("computer-use", "/bin/computer-use", listOf("mcp"))

    private fun TestScope.adapter(session: FakeSession) =
        McpAdapter(listOf(config), backgroundScope) { _, onChanged ->
            session.also {
                it.onChanged =
                    onChanged
            }
        }

    @Test
    fun `a server that cannot start is listed with why, and offers nothing`() =
        runTest {
            val adapter = McpAdapter(listOf(config), backgroundScope) { _, _ -> error("no such file") }
            adapter.scan()
            val entry = adapter.installed.value.single()
            assertNull(entry.descriptor)
            assertTrue(entry.problem!!.contains("no such file"))
        }

    @Test
    fun `calls carry typed arguments, and a stopped or silent server is never reported as done`() =
        runTest {
            val session = FakeSession()
            val adapter = adapter(session)
            adapter.scan()
            val binding = adapter.bindings(adapter.installed.value.single()).single()
            assertEquals("mcp.computer-use.move_window", binding.definition.id)

            fun call() = ToolProposal("call-${session.calls.size}", binding.definition.id, mapOf("x" to "40"), "move it", "r")

            assertEquals(InvocationStatus.COMPLETED, binding.backend.execute(call()).status)
            assertEquals(JsonPrimitive(40), session.calls.single().second["x"])

            session.failure = McpNotSent("the MCP server had stopped")
            assertEquals(InvocationStatus.NOT_EXECUTED, binding.backend.execute(call()).status)

            session.failure = IllegalStateException("connection reset")
            assertEquals(InvocationStatus.UNKNOWN, binding.backend.execute(call()).status)

            session.running = false
            assertEquals(InvocationStatus.NOT_EXECUTED, binding.backend.execute(call()).status)
        }

    @Test
    fun `a tool reaches the catalog only once the user allows it`() =
        runTest {
            val session = FakeSession()
            val adapter = adapter(session)
            val registry = CapabilityRegistry(emptyMap(), emptyList())
            val runtime = ExtensionRuntime(registry, adapter, ExtensionGrants(MemoryGrantPersistence()), backgroundScope)
            runCurrent()
            runtime.awaitReady()
            assertTrue(registry.catalog.isEmpty())

            val key =
                runtime.settings.value.entries
                    .single()
                    .key
            runtime.enable(key, true)
            runCurrent()
            assertTrue("Enabling a server grants none of its unknown-effect tools", registry.catalog.isEmpty())

            runtime.mutation(key, "move_window", true)
            runtime.settings.first {
                it.entries
                    .single()
                    .mutations
                    .isNotEmpty()
            }
            assertEquals(listOf("mcp.computer-use.move_window"), registry.catalog.map { it.id })

            val receipt =
                CapabilityDispatcher(registry, MemoryInvocationRepository()).execute(
                    ToolProposal("call", "mcp.computer-use.move_window", mapOf("x" to "5"), "move it", registry.snapshot.revision),
                )
            assertEquals(InvocationStatus.COMPLETED, receipt.status)
        }

    @Test
    fun `a server announcing changed tools is withdrawn at once and its new contract needs approval again`() =
        runTest {
            val session = FakeSession()
            val adapter = adapter(session)
            val registry = CapabilityRegistry(emptyMap(), emptyList())
            val runtime = ExtensionRuntime(registry, adapter, ExtensionGrants(MemoryGrantPersistence()), backgroundScope)
            runCurrent()
            runtime.awaitReady()
            val granted =
                runtime.settings.value.entries
                    .single()
            runtime.enable(granted.key, true)
            runCurrent()
            runtime.mutation(granted.key, "move_window", true)
            runtime.settings.first {
                it.entries
                    .single()
                    .mutations
                    .isNotEmpty()
            }
            val identity = granted.installed.identity as McpIdentity
            assertTrue(adapter.available(identity, granted.installed.descriptor!!.digest))

            session.description = "Move a window, or close it"
            session.onChanged()
            assertTrue("Withdrawn before any new listing", !adapter.available(identity, granted.installed.descriptor!!.digest))

            runCurrent()
            val relisted =
                runtime.settings.first {
                    it.entries
                        .single()
                        .installed.descriptor
                        ?.digest !=
                        granted.installed.descriptor!!.digest
                }
            assertTrue(
                relisted.entries
                    .single()
                    .mutations
                    .isEmpty(),
            )
            assertTrue(registry.catalog.isEmpty())
        }

    @Test
    fun `a change announced while another server is still listing keeps the changed server withdrawn`() =
        runTest {
            val fast = FakeSession()
            val slow = FakeSession()
            // Rescans are driven by hand here, so a notice's own refresh cannot hide the race.
            val inert = CoroutineScope(Job().apply { cancel() })
            val adapter =
                McpAdapter(listOf(config, McpServerConfig("slow", "/bin/slow")), inert) { started, onChanged ->
                    (if (started.name == "slow") slow else fast).also { it.onChanged = onChanged }
                }
            adapter.scan()
            val listed = adapter.installed.value.first { it.packageName == config.name }
            val identity = listed.identity as McpIdentity
            val digest = listed.descriptor!!.digest
            assertTrue(adapter.available(identity, digest))

            fast.onChanged()
            assertTrue(!adapter.available(identity, digest))
            val held = CompletableDeferred<Unit>()
            slow.gateOverride = held
            // The slow server relists too, so the fast one's listing waits for it before publication.
            slow.onChanged()
            val relisting = backgroundScope.launch { adapter.scan() }
            runCurrent()
            fast.onChanged()
            held.complete(Unit)
            relisting.join()
            assertTrue("A notice that arrived during listing must not be cleared", !adapter.available(identity, digest))

            adapter.scan()
            assertTrue(adapter.available(identity, digest))
        }

    @Test
    fun `a scan cut short closes the servers it started but never published`() =
        runTest {
            val fast = FakeSession()
            val slow = FakeSession(CompletableDeferred())
            val adapter =
                McpAdapter(listOf(config, McpServerConfig("slow", "/bin/slow")), backgroundScope) { started, _ ->
                    if (started.name == "slow") slow else fast
                }
            val scan = backgroundScope.launch { adapter.scan() }
            runCurrent()
            scan.cancelAndJoin()
            assertTrue(!fast.running && !slow.running)
        }
}
