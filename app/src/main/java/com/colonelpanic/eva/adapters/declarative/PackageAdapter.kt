package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.BoundedExecution
import com.colonelpanic.eva.capability.BudgetedBackend
import com.colonelpanic.eva.capability.CapabilityDefinition
import com.colonelpanic.eva.capability.CapabilitySource
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.capability.WaitBudget
import com.colonelpanic.eva.capability.extensions.AdapterIdentity
import com.colonelpanic.eva.capability.extensions.Capability
import com.colonelpanic.eva.capability.extensions.CapabilityAdapter
import com.colonelpanic.eva.capability.extensions.CapabilityBinding
import com.colonelpanic.eva.capability.extensions.Descriptor
import com.colonelpanic.eva.capability.extensions.ExtensionProtocol
import com.colonelpanic.eva.capability.extensions.InstalledExtension
import com.colonelpanic.eva.capability.extensions.PackageIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LoadedPackage(
    val identity: PackageIdentity,
    val definition: PackageDefinition,
    val configured: Boolean,
)

class PackageAdapter(
    private val load: () -> List<LoadedPackage>,
    private val host: (PackageIdentity) -> DeclarativeHost,
    private val execution: BoundedExecution,
    private val budget: (PackageIdentity, PackageCapability, ToolProposal) -> WaitBudget,
) : CapabilityAdapter {
    private var packages = emptyList<LoadedPackage>()
    private val entries = MutableStateFlow<List<InstalledExtension>>(emptyList())
    override val installed = entries.asStateFlow()
    override val ready = MutableStateFlow(false)

    @Synchronized
    override fun refresh() {
        packages = load()
        entries.value =
            packages.map { item ->
                val definition = item.definition
                InstalledExtension(
                    definition.id,
                    item.identity,
                    Descriptor(
                        definition.digest,
                        definition.digest,
                        definition.title,
                        definition.capabilities.map { capability ->
                            Capability(
                                capability.name,
                                capability.title,
                                capability.description,
                                capability.inputSchema,
                                capability.effect.toEffect(),
                                capability.execution.maxWaitMillis ?: ExtensionProtocol.DEFAULT_WAIT_MILLIS,
                                ExtensionProtocol.RESULT_BYTES,
                                capability.outputSchema,
                                capability.annotations,
                            )
                        },
                        definition.digest,
                    ),
                    if (item.configured) null else "Configure the approved server URL and credential before enabling.",
                    capabilityPrefix = "extension.package.${item.identity.id}",
                    androidPackages = definition.appTargets(),
                )
            }
        ready.value = true
    }

    override fun invalidate(
        packageName: String,
        removed: Boolean,
    ) = Unit

    @Synchronized
    override fun available(
        identity: AdapterIdentity,
        digest: String,
    ): Boolean = entries.value.any { it.identity == identity && it.descriptor?.digest == digest && it.problem == null }

    @Synchronized
    override fun bindings(extension: InstalledExtension): List<CapabilityBinding> {
        val item =
            packages.find { it.identity == extension.identity && it.definition.digest == extension.descriptor?.digest }
                ?: return emptyList()
        return item.definition.capabilities.map { capability ->
            val descriptor = checkNotNull(extension.descriptor)
            CapabilityBinding(
                descriptor.capabilities.single { it.name == capability.name },
                CapabilityDefinition(
                    "${extension.capabilityPrefix}.${capability.name}",
                    capability.title,
                    capability.description + " May take up to the configured wait budget; a timeout does not undo the action.",
                    capability.inputSchema,
                    capability.effect == PackageEffect.READ,
                    CapabilitySource(item.identity.instanceId, item.definition.title),
                    item.definition.guidance,
                ),
                BudgetedBackend(
                    item.identity.instanceId,
                    DeclarativeBackend(capability, host(item.identity)) { budget(item.identity, capability, it) },
                    execution,
                ) { budget(item.identity, capability, it) },
                item.definition.digest,
            )
        }
    }
}
