package com.colonelpanic.eva.capability.extensions

import com.colonelpanic.eva.capability.CapabilityDefinition
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.CapabilitySource
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.capability.ToolProposal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CapabilityAdapterTest {
    @Test
    fun `non-service adapter gets common settings grants and journaled provenance`() =
        runTest {
            val descriptor = ExtensionProtocol.describe(extensionDescription).descriptor!!
            val identity = extensionIdentity
            var executions = 0
            val backend =
                object : ExecutionBackend {
                    override suspend fun unavailableReason(): String? = null

                    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                        executions++
                        return ExecutionOutcome(InvocationStatus.COMPLETED, "Adapter evidence")
                    }
                }
            val definition =
                CapabilityDefinition(
                    "extension.example.app.read",
                    "Read",
                    "Read data",
                    extensionSchema,
                    readOnly = true,
                    source = CapabilitySource(identity.component, "Example"),
                )
            val binding = CapabilityBinding(extensionCapability, definition, backend, "approved-binding")
            val adapter =
                object : CapabilityAdapter {
                    override val installed = MutableStateFlow(listOf(InstalledExtension(identity.packageName, identity, descriptor)))
                    override val ready = MutableStateFlow(true)

                    override fun refresh() = Unit

                    override fun invalidate(
                        packageName: String,
                        removed: Boolean,
                    ) {
                        installed.value = emptyList()
                    }

                    override fun available(
                        identity: ExtensionIdentity,
                        digest: String,
                    ) = installed.value.isNotEmpty()

                    override fun bindings(extension: InstalledExtension) = listOf(binding)
                }
            val registry = CapabilityRegistry(emptyMap())
            val runtime = ExtensionRuntime(registry, adapter, ExtensionGrants(MemoryGrantPersistence()), backgroundScope)
            runCurrent()
            assertFalse(
                runtime.settings.value.entries
                    .single()
                    .enabled,
            )
            assertTrue(registry.catalog.isEmpty())
            val key =
                runtime.settings.value.entries
                    .single()
                    .key
            runtime.enable(key, true)
            runCurrent()
            val journal = MemoryInvocationRepository()
            val dispatcher = CapabilityDispatcher(registry, journal)
            val proposal = ToolProposal("first", definition.id, emptyMap(), "read", registry.snapshot.revision)
            val record = dispatcher.execute(proposal)
            assertEquals(InvocationStatus.COMPLETED, record.status)
            assertEquals(definition.source, record.provenance!!.source)
            assertEquals(binding.revision, record.provenance.bindingRevision)
            runtime.enable(key, false)
            runCurrent()
            assertEquals(InvocationStatus.NOT_EXECUTED, dispatcher.execute(proposal.copy(callId = "revoked")).status)
            assertEquals(1, executions)
        }
}
