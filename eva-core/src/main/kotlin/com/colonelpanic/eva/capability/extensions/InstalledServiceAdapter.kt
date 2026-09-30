package com.colonelpanic.eva.capability.extensions

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class InstalledServiceAdapter(
    private val discovery: ExtensionDiscovery,
    private val connections: ExtensionConnectionManager,
    private val worker: CoroutineDispatcher = Dispatchers.IO,
) : CapabilityAdapter {
    override val installed = discovery.installed
    override val ready = discovery.ready

    override fun refresh() = discovery.requestRefresh()

    override fun invalidate(
        packageName: String,
        removed: Boolean,
    ) = discovery.invalidate(packageName, removed)

    override fun available(
        identity: AdapterIdentity,
        digest: String,
    ): Boolean = identity is ExtensionIdentity && discovery.available(identity, digest)

    override fun bindings(extension: InstalledExtension): List<CapabilityBinding> {
        val identity = extension.identity as? ExtensionIdentity ?: return emptyList()
        val descriptor = extension.descriptor ?: return emptyList()
        return descriptor.capabilities.map { capability ->
            val backend = ExtensionBackend(identity, descriptor, capability, connections, worker)
            CapabilityBinding(capability, backend.definition, backend, "${identity.key}:${descriptor.digest}")
        }
    }
}
