package com.colonelpanic.eva.capability.extensions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class CompositeCapabilityAdapter(
    private val adapters: List<CapabilityAdapter>,
    scope: CoroutineScope,
) : CapabilityAdapter {
    private val entries = MutableStateFlow<List<InstalledExtension>>(emptyList())
    private val initialized = MutableStateFlow(adapters.isEmpty())
    override val installed = entries.asStateFlow()
    override val ready = initialized.asStateFlow()

    init {
        if (adapters.isNotEmpty()) {
            scope.launch {
                combine(
                    adapters.map { adapter ->
                        combine(adapter.ready, adapter.installed) { ready, entries -> ready to entries }
                    },
                ) { states ->
                    states.all { it.first } to states.flatMap { it.second }
                }.collect { (ready, installed) ->
                    val identities = installed.mapNotNull { it.identity?.instanceId }
                    require(identities.size == identities.distinct().size) { "Duplicate adapter instance identity" }
                    entries.value = installed.sortedBy { it.identity?.instanceId ?: it.packageName }
                    initialized.value = ready
                }
            }
        }
    }

    override fun refresh() = adapters.forEach { it.refresh() }

    override fun invalidate(
        packageName: String,
        removed: Boolean,
    ) = adapters.forEach { it.invalidate(packageName, removed) }

    override fun available(
        identity: AdapterIdentity,
        digest: String,
    ): Boolean = adapters.any { it.available(identity, digest) }

    override fun bindings(extension: InstalledExtension): List<CapabilityBinding> =
        adapters.filter { adapter -> adapter.installed.value.any { it.identity == extension.identity } }.flatMap { it.bindings(extension) }
}
