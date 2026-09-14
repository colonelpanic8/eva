package com.colonelpanic.eva.capability.extensions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IsolatedCapabilityAdapterTest {
    @Test
    fun `initializer failure is visible unavailable and does not block a healthy sibling`() =
        runTest {
            val reports = mutableListOf<Throwable>()
            val bad = IsolatedCapabilityAdapter("Broken", backgroundScope, reports::add) { throw ExceptionInInitializerError("regex") }
            val good = fake()
            val composite = CompositeCapabilityAdapter(listOf(bad, good), backgroundScope)
            composite.refresh()
            runCurrent()
            assertTrue(composite.ready.value)
            assertEquals(
                setOf("Broken", "Healthy"),
                composite.installed.value
                    .map { it.packageName }
                    .toSet(),
            )
            assertTrue(
                bad.installed.value
                    .single()
                    .problem != null,
            )
            assertEquals(1, reports.size)
        }

    @Test
    fun `refresh failure revokes availability even when the delegate retains its old state`() =
        runTest {
            val identity = PackageIdentity("00000000-0000-0000-0000-000000000001")
            val delegate = fake { error("refresh failed") }
            val reports = mutableListOf<Throwable>()
            val isolated = IsolatedCapabilityAdapter("Broken", backgroundScope, reports::add) { delegate }
            runCurrent()
            assertTrue(isolated.available(identity, "digest"))
            isolated.refresh()
            assertFalse(isolated.available(identity, "digest"))
            assertTrue(isolated.bindings(isolated.installed.value.single()).isEmpty())
            assertTrue(isolated.installed.value.all { it.problem != null })
            assertEquals(1, reports.size)
        }

    @Test
    fun `discovery initializer failure becomes ready unavailable and a later scan recovers`() =
        runTest {
            var broken = true
            val reports = mutableListOf<Throwable>()
            val connections = ExtensionConnectionManager(FakeExtensionConnector()) { testScheduler.currentTime }
            val discovery =
                ExtensionDiscovery(
                    { if (broken) throw ExceptionInInitializerError("scan failed") else emptyList() },
                    connections,
                    backgroundScope,
                    reports::add,
                )
            discovery.requestRefresh()
            runCurrent()
            advanceTimeBy(251)
            runCurrent()
            assertTrue(discovery.ready.value)
            assertTrue(
                discovery.installed.value
                    .single()
                    .problem != null,
            )
            assertEquals(1, reports.size)
            broken = false
            discovery.requestRefresh()
            runCurrent()
            advanceTimeBy(251)
            runCurrent()
            assertTrue(discovery.installed.value.isEmpty())
        }

    private fun fake(refresh: () -> Unit = {}) =
        object : CapabilityAdapter {
            override val ready = MutableStateFlow(true)
            override val installed = MutableStateFlow(listOf(InstalledExtension("Healthy", null, null)))

            override fun refresh() = refresh.invoke()

            override fun invalidate(
                packageName: String,
                removed: Boolean,
            ) = Unit

            override fun available(
                identity: AdapterIdentity,
                digest: String,
            ) = true

            override fun bindings(extension: InstalledExtension) = emptyList<CapabilityBinding>()
        }
}
