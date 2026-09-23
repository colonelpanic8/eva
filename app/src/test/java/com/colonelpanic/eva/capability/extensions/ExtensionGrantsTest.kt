package com.colonelpanic.eva.capability.extensions

import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.capability.ToolProposal
import kotlinx.coroutines.async
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
    var writes = 0

    override suspend fun read() = json

    override suspend fun write(json: String) {
        check(!fail)
        writes++
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
    fun `enable all saves one grant for reads and every non-read effect`() =
        runTest {
            val disk = MemoryGrantPersistence()
            val grants = ExtensionGrants(disk)

            grants.enableAll(extensionIdentity, all)

            assertEquals(1, disk.writes)
            assertEquals(setOf("write", "unknown"), grants.grant(extensionIdentity, all)?.mutations)
            val restored = ExtensionGrants(disk)
            restored.load()
            assertTrue(restored.allowed(extensionIdentity, all, extensionCapability))
            assertTrue(restored.allowed(extensionIdentity, all, write))
            assertTrue(restored.allowed(extensionIdentity, all, unknown))
        }

    @Test
    fun `catalog rebind approves only newly named actions and requires the previous digest`() =
        runTest {
            val grants = ExtensionGrants(MemoryGrantPersistence())
            val previous = all.copy(digest = "a".repeat(64))
            val later =
                all.copy(
                    digest = "b".repeat(64),
                    capabilities = all.capabilities + write.copy(name = "new_action"),
                )
            grants.enableAll(extensionIdentity, previous)
            grants.mutation(extensionIdentity, previous, "unknown", false)

            assertFalse(grants.rebind(extensionIdentity, later, "wrong", setOf("write", "unknown"), true))
            assertEquals(previous.digest, grants.all().getValue(extensionIdentity.instanceId).digest)
            assertTrue(grants.rebind(extensionIdentity, later, previous.digest, setOf("write", "unknown"), true))
            assertEquals(setOf("write", "new_action"), grants.grant(extensionIdentity, later)?.mutations)

            val effectChange =
                later.copy(
                    digest = "c".repeat(64),
                    capabilities =
                        later.capabilities.map {
                            if (it.name ==
                                extensionCapability.name
                            ) {
                                it.copy(effect = Effect.WRITE)
                            } else {
                                it
                            }
                        },
                )
            assertTrue(
                grants.rebind(
                    extensionIdentity,
                    effectChange,
                    later.digest,
                    later.capabilities.map { it.name }.toSet(),
                    true,
                ),
            )
            assertFalse(
                "An existing read cannot gain write permission",
                extensionCapability.name in grants.grant(extensionIdentity, effectChange)!!.mutations,
            )
            assertFalse("An explicitly declined action stays off", "unknown" in grants.grant(extensionIdentity, effectChange)!!.mutations)
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
            val ready = backgroundScope.async { runtime.awaitReady() }
            runCurrent()
            assertFalse(ready.isCompleted)
            advanceTimeBy(251)
            runCurrent()
            assertTrue(ready.isCompleted)
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

    @Test
    fun `a default provider gets every action and keeps declined ones across a contract change`() =
        runTest {
            val disk = MemoryGrantPersistence()
            val grants = ExtensionGrants(disk)
            val installed = listOf(InstalledExtension("example.app", extensionIdentity, all))
            assertEquals(setOf(extensionIdentity.instanceId), grants.reconcile(installed) { true })
            assertTrue(grants.allowed(extensionIdentity, all, write))
            assertTrue(grants.allowed(extensionIdentity, all, unknown))
            grants.mutation(extensionIdentity, all, "write", false)

            val reloaded = ExtensionGrants(disk).apply { load() }
            val account = all.copy(revision = "v2", digest = "changed")
            reloaded.reconcile(listOf(InstalledExtension("example.app", extensionIdentity, account))) { true }
            assertFalse(reloaded.allowed(extensionIdentity, account, write))
            assertTrue(reloaded.allowed(extensionIdentity, account, unknown))

            val other = ExtensionGrants(MemoryGrantPersistence())
            other.enableAll(extensionIdentity, all)
            assertEquals(
                emptySet<String>(),
                other.reconcile(listOf(InstalledExtension("example.app", extensionIdentity, account))) { false },
            )
            assertFalse(other.allowed(extensionIdentity, account, extensionCapability))
        }

    @Test
    fun `turning a default provider off is remembered and turning it on restores every action`() =
        runTest {
            val fake = FakeExtensionConnector().apply { reply = extensionDescription }
            val connections = ExtensionConnectionManager(fake) { testScheduler.currentTime }
            val discovery =
                ExtensionDiscovery({ listOf(ExtensionCandidate(extensionIdentity, true, true, 1)) }, connections, backgroundScope)
            val registry = CapabilityRegistry(emptyMap())
            val choices = mutableMapOf<String, Boolean>()
            val policy =
                object : DefaultGrantPolicy {
                    override fun trusts(identity: AdapterIdentity) = identity == extensionIdentity

                    override fun autoEnable(instance: String) = choices[instance] ?: true

                    override fun setAutoEnable(
                        instance: String,
                        enabled: Boolean,
                    ) {
                        choices[instance] = enabled
                    }
                }
            val runtime =
                ExtensionRuntime(
                    registry,
                    InstalledServiceAdapter(discovery, connections, StandardTestDispatcher(testScheduler)),
                    ExtensionGrants(MemoryGrantPersistence()),
                    backgroundScope,
                    defaults = policy,
                )
            advanceTimeBy(251)
            runCurrent()
            val entry =
                runtime.settings.value.entries
                    .single()
            assertTrue(entry.enabled)
            assertEquals(1, registry.catalog.size)

            runtime.enable(entry.key, false)
            runCurrent()
            assertEquals(false, choices[extensionIdentity.instanceId])
            runtime.refresh()
            advanceTimeBy(251)
            runCurrent()
            assertFalse(
                runtime.settings.value.entries
                    .single()
                    .enabled,
            )
            assertTrue(registry.catalog.isEmpty())

            runtime.enable(entry.key, true)
            runCurrent()
            assertEquals(true, choices[extensionIdentity.instanceId])
            assertTrue(
                runtime.settings.value.entries
                    .single()
                    .enabled,
            )
        }

    @Test
    fun `only the pinned production signer of a default provider is trusted`() {
        val mova =
            ExtensionIdentity(
                0,
                "com.colonelpanic.mova",
                "com.colonelpanic.mova/.eva.EvaExtensionService",
                "905afc8729daa77fff81b20d99b169f919879379a8e7dbe23546e350ed46ad22",
                10123,
                1,
            )
        assertTrue(DefaultProviders.trusts(mova))
        assertFalse(DefaultProviders.trusts(mova.copy(signer = "fac61745dc0903786fb9ede62a962b399f7348f0bb6f899b8332667591033b9c")))
        assertFalse(DefaultProviders.trusts(mova.copy(packageName = "com.colonelpanic.mova.debug")))
        assertFalse(DefaultProviders.trusts(PackageIdentity("00000000-0000-0000-0000-000000000001")))
    }
}
