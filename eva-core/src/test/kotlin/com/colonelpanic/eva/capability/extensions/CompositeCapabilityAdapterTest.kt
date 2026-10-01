package com.colonelpanic.eva.capability.extensions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CompositeCapabilityAdapterTest {
    @Test
    fun `same publisher from distinct adapters stays separate and removal gates before publication`() =
        runTest {
            val descriptor = ExtensionProtocol.describe(extensionDescription).descriptor!!

            fun adapter(identity: AdapterIdentity) =
                object : CapabilityAdapter {
                    override val installed = MutableStateFlow(listOf(InstalledExtension("example.app", identity, descriptor)))
                    override val ready = MutableStateFlow(false)

                    override fun refresh() = Unit

                    override fun invalidate(
                        packageName: String,
                        removed: Boolean,
                    ) {
                        if (identity is ExtensionIdentity && removed) installed.value = emptyList()
                    }

                    override fun available(
                        identity: AdapterIdentity,
                        digest: String,
                    ) = installed.value.any { it.identity == identity }

                    override fun bindings(extension: InstalledExtension) = emptyList<CapabilityBinding>()
                }
            val packageIdentity = PackageIdentity("00000000-0000-0000-0000-000000000001")
            val installed = adapter(extensionIdentity)
            val packages = adapter(packageIdentity)
            val combined = CompositeCapabilityAdapter(listOf(installed, packages), backgroundScope)
            runCurrent()
            assertFalse(combined.ready.value)
            installed.ready.value = true
            packages.ready.value = true
            runCurrent()
            assertTrue(combined.ready.value)
            assertEquals(2, combined.installed.value.size)
            combined.invalidate("example.app", true)
            assertFalse(combined.available(extensionIdentity, descriptor.digest))
            assertTrue(combined.available(packageIdentity, descriptor.digest))
            runCurrent()
            assertEquals(
                packageIdentity,
                combined.installed.value
                    .single()
                    .identity,
            )
        }
}
