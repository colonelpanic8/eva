package com.colonelpanic.eva.capability.extensions

import com.colonelpanic.eva.capability.CapabilityDefinition
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SupersessionTest {
    private val app =
        InstalledExtension(
            extensionIdentity.packageName,
            extensionIdentity,
            Descriptor("app", "app", "Example", listOf(extensionCapability), "app"),
        )
    private val packageIdentity = PackageIdentity("00000000-0000-0000-0000-000000000001")
    private val catalogPackage =
        InstalledExtension(
            "android.example",
            packageIdentity,
            Descriptor(
                "package",
                "package",
                "Example",
                listOf(extensionCapability, extensionCapability.copy(name = "open", title = "Open", effect = Effect.HANDOFF)),
                "package",
            ),
            capabilityPrefix = "extension.package.${packageIdentity.id}",
            androidPackages = listOf(extensionIdentity.packageName),
        )

    @Test
    fun `an app's own extension replaces a catalog package's same-named actions until it goes away`() =
        runTest {
            val adapter = FakeAdapter(listOf(app, catalogPackage))
            val registry = CapabilityRegistry(emptyMap())
            val runtime = ExtensionRuntime(registry, adapter, ExtensionGrants(MemoryGrantPersistence()), backgroundScope)
            runCurrent()
            runtime.settings.value.entries
                .forEach { runtime.enableAll(it.key) }
            runCurrent()

            assertEquals(
                setOf("extension.example.app.read", "extension.package.${packageIdentity.id}.open"),
                registry.catalog.map { it.id }.toSet(),
            )
            val folded =
                runtime.settings.value.entries
                    .single { it.installed.identity == packageIdentity }
            assertEquals(Supersession(extensionIdentity.instanceId, setOf("read")), folded.supersession)

            adapter.installed.value = listOf(catalogPackage)
            runCurrent()

            assertEquals(
                setOf("extension.package.${packageIdentity.id}.read", "extension.package.${packageIdentity.id}.open"),
                registry.catalog.map { it.id }.toSet(),
            )
            assertEquals(
                null,
                runtime.settings.value.entries
                    .single()
                    .supersession,
            )
        }

    private class FakeAdapter(
        entries: List<InstalledExtension>,
    ) : CapabilityAdapter {
        override val installed = MutableStateFlow(entries)
        override val ready = MutableStateFlow(true)

        override fun refresh() = Unit

        override fun invalidate(
            packageName: String,
            removed: Boolean,
        ) = Unit

        override fun available(
            identity: AdapterIdentity,
            digest: String,
        ) = installed.value.any { it.identity == identity }

        override fun bindings(extension: InstalledExtension) =
            checkNotNull(extension.descriptor).capabilities.map { capability ->
                val id = "${extension.capabilityPrefix}.${capability.name}"
                CapabilityBinding(
                    capability,
                    CapabilityDefinition(id, capability.title, capability.description, extensionSchema),
                    Backend,
                    id,
                )
            }
    }

    private object Backend : ExecutionBackend {
        override suspend fun unavailableReason(): String? = null

        override suspend fun execute(arguments: Map<String, String>) = ExecutionOutcome(InvocationStatus.COMPLETED, "done")
    }
}
