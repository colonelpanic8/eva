package com.colonelpanic.eva.capability.extensions

import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.capability.ToolProposal
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class MemoryGrantPersistence : ExtensionGrantPersistence {
    var json: String? = null
    var fail = false

    override suspend fun read() = json

    override suspend fun write(json: String) {
        check(!fail)
        this.json = json
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExtensionGrantsTest {
    @Test
    fun `same declared package does not share grants between import instances or transports`() =
        runTest {
            val descriptor = ExtensionProtocol.describe(extensionDescription).descriptor!!
            val one = PackageIdentity("00000000-0000-0000-0000-000000000001")
            val two = PackageIdentity("00000000-0000-0000-0000-000000000002")
            val grants = ExtensionGrants(MemoryGrantPersistence())
            grants.enable(one, descriptor, true)
            grants.enable(extensionIdentity, descriptor, true)
            assertTrue(grants.allowed(one, descriptor, extensionCapability))
            assertFalse(grants.allowed(two, descriptor, extensionCapability))
            grants.reconcile(
                listOf(
                    InstalledExtension("example.app", extensionIdentity, descriptor),
                    InstalledExtension("example.app", one, descriptor),
                ),
            )
            assertTrue(grants.allowed(one, descriptor, extensionCapability))
            assertTrue(grants.allowed(extensionIdentity, descriptor, extensionCapability))
            grants.remove(one.instanceId)
            assertFalse(grants.allowed(one, descriptor, extensionCapability))
            assertTrue(grants.allowed(extensionIdentity, descriptor, extensionCapability))
        }

    private val descriptor = ExtensionProtocol.describe(extensionDescription).descriptor!!
    private val write = extensionCapability.copy(name = "write", effect = Effect.WRITE)
    private val unknown = extensionCapability.copy(name = "unknown", effect = Effect.UNKNOWN)
    private val all = descriptor.copy(capabilities = listOf(extensionCapability, write, unknown))

    @Test
    fun `enable grants only claimed reads and mutations persist individually`() =
        runTest {
            val disk = MemoryGrantPersistence()
            val grants = ExtensionGrants(disk)
            grants.load()
            assertFalse(grants.allowed(extensionIdentity, all, extensionCapability))
            grants.enable(extensionIdentity, all, true)
            assertTrue(grants.allowed(extensionIdentity, all, extensionCapability))
            assertFalse(grants.allowed(extensionIdentity, all, write))
            assertFalse(grants.allowed(extensionIdentity, all, unknown))
            grants.mutation(extensionIdentity, all, "write", true)
            val restored = ExtensionGrants(disk)
            restored.load()
            assertTrue(restored.allowed(extensionIdentity, all, write))
            assertFalse(restored.allowed(extensionIdentity, all, unknown))
            restored.enable(extensionIdentity, all, false)
            restored.enable(extensionIdentity, all, true)
            assertFalse(restored.allowed(extensionIdentity, all, write))
        }

    @Test
    fun `all identity boundaries and authorization scope require renewed grants`() =
        runTest {
            val grants = ExtensionGrants(MemoryGrantPersistence())
            grants.enable(extensionIdentity, descriptor, true)
            for (identity in listOf(
                extensionIdentity.copy(user = 1),
                extensionIdentity.copy(packageName = "another.app"),
                extensionIdentity.copy(component = "example.app/.Other"),
                extensionIdentity.copy(signer = "other"),
                extensionIdentity.copy(installedAt = 2),
            )) {
                assertFalse(grants.allowed(identity, descriptor, extensionCapability))
            }
            val changed = ExtensionProtocol.describe(extensionDescription.replace("account1", "account2")).descriptor!!
            assertEquals(descriptor.revision, changed.revision)
            assertNotEquals(descriptor.digest, changed.digest)
            assertFalse(grants.allowed(extensionIdentity, changed, extensionCapability))
            grants.reconcile(listOf(InstalledExtension(extensionIdentity.packageName, extensionIdentity, changed)))
            assertFalse(grants.allowed(extensionIdentity, descriptor, extensionCapability))
        }

    @Test
    fun `portable restore accepts only the exact live identity digest and mutation names`() =
        runTest {
            val disk = MemoryGrantPersistence()
            val grants = ExtensionGrants(disk)
            val requested =
                mapOf(
                    extensionIdentity.instanceId to ExtensionGrant(extensionIdentity.key, all.digest, setOf("write")),
                    "missing" to ExtensionGrant("missing-key", "c".repeat(64)),
                )

            val missing = grants.restore(requested, listOf(InstalledExtension("example.app", extensionIdentity, all)))

            assertEquals(setOf("missing"), missing)
            assertTrue(grants.allowed(extensionIdentity, all, extensionCapability))
            assertTrue(grants.allowed(extensionIdentity, all, write))

            val changed = all.copy(digest = "d".repeat(64))
            assertEquals(
                setOf(extensionIdentity.instanceId),
                grants.restore(
                    mapOf(extensionIdentity.instanceId to ExtensionGrant(extensionIdentity.key, all.digest, setOf("write"))),
                    listOf(InstalledExtension("example.app", extensionIdentity, changed)),
                ),
            )
            assertFalse(grants.allowed(extensionIdentity, all, extensionCapability))

            assertEquals(
                setOf(extensionIdentity.instanceId),
                grants.restore(
                    mapOf(extensionIdentity.instanceId to ExtensionGrant(extensionIdentity.key, all.digest, setOf("not_a_capability"))),
                    listOf(InstalledExtension("example.app", extensionIdentity, all)),
                ),
            )
            assertFalse(grants.allowed(extensionIdentity, all, extensionCapability))
        }

    @Test
    fun `removal prunes persisted grants while outages preserve them and storage failure fails closed`() =
        runTest {
            val disk = MemoryGrantPersistence()
            val grants = ExtensionGrants(disk)
            grants.enable(extensionIdentity, descriptor, true)
            grants.reconcile(listOf(InstalledExtension(extensionIdentity.packageName, extensionIdentity, null, "unreachable")))
            assertTrue(grants.allowed(extensionIdentity, descriptor, extensionCapability))
            disk.fail = true
            runCatching { grants.enable(extensionIdentity, descriptor, false) }
            assertFalse(grants.allowed(extensionIdentity, descriptor, extensionCapability))
            disk.fail = false
            grants.remove(extensionIdentity.instanceId)
            val restored = ExtensionGrants(disk)
            restored.load()
            assertFalse(restored.allowed(extensionIdentity, descriptor, extensionCapability))
        }

    @Test
    fun `grant is rechecked after preflight before dispatch journal transition`() =
        runTest {
            val journal = MemoryInvocationRepository()
            var allowed = true
            var executions = 0
            val definition =
                com.colonelpanic.eva.capability
                    .CapabilityDefinition("extension.example.read", "Read", "Read", extensionSchema)
            val backend =
                object : ExecutionBackend {
                    override suspend fun unavailableReason(): String? {
                        allowed = false
                        return null
                    }

                    override fun dispatchRejection(): String? = if (allowed) null else "Permission revoked"

                    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                        executions++
                        return ExecutionOutcome(InvocationStatus.COMPLETED, "done")
                    }
                }
            val registry = CapabilityRegistry(mapOf(definition.id to backend), listOf(definition))
            val result =
                CapabilityDispatcher(registry, journal).execute(
                    ToolProposal("call", definition.id, emptyMap(), "read", registry.snapshot.revision),
                )
            assertEquals(InvocationStatus.NOT_EXECUTED, result.status)
            assertEquals("Permission revoked", result.message)
            assertEquals(0, executions)
        }

    @Test
    fun `runtime discovers disabled installs grants and invalidates open snapshots on removal`() =
        runTest {
            val fake = FakeExtensionConnector().apply { reply = extensionDescription }
            var candidates = listOf(ExtensionCandidate(extensionIdentity, true, true, 1))
            val connections = ExtensionConnectionManager(fake) { testScheduler.currentTime }
            val discovery = ExtensionDiscovery({ candidates }, connections, backgroundScope)
            val registry = CapabilityRegistry(emptyMap())
            val runtime =
                ExtensionRuntime(
                    registry,
                    InstalledServiceAdapter(discovery, connections, StandardTestDispatcher(testScheduler)),
                    ExtensionGrants(MemoryGrantPersistence()),
                    backgroundScope,
                )
            runCurrent()
            advanceTimeBy(251)
            runCurrent()
            assertTrue(registry.catalog.isEmpty())
            assertFalse(
                runtime.settings.value.entries
                    .single()
                    .enabled,
            )
            runtime.enable(
                runtime.settings.value.entries
                    .single()
                    .key,
                true,
            )
            runCurrent()
            val snapshot = registry.snapshot
            assertEquals(1, snapshot.catalog.size)
            fake.reply = ExtensionProtocol.encodeResult(ResultReply(ExecutionOutcome(InvocationStatus.COMPLETED, "data"), null, false))
            val journal = MemoryInvocationRepository()
            val dispatcher = CapabilityDispatcher(registry, journal)
            val proposal = ToolProposal("call", snapshot.catalog.single().id, emptyMap(), "read", snapshot.revision)
            assertEquals(InvocationStatus.COMPLETED, dispatcher.execute(proposal).status)
            candidates = emptyList()
            runtime.packageChanged(extensionIdentity.packageName, true)
            assertFalse(discovery.available(extensionIdentity, descriptor.digest))
            runCurrent()
            assertEquals(InvocationStatus.NOT_EXECUTED, dispatcher.execute(proposal.copy(callId = "after-removal")).status)
            assertTrue(registry.catalog.isEmpty())
        }
}
