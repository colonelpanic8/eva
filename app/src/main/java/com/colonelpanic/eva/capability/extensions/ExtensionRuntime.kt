package com.colonelpanic.eva.capability.extensions

import com.colonelpanic.eva.capability.CapabilityRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ExtensionSettingsEntry(
    val installed: InstalledExtension,
    val enabled: Boolean,
    val mutations: Set<String>,
) {
    val key: String get() = "${installed.identity?.key}:${installed.descriptor?.digest}"
}

data class ExtensionSettings(
    val entries: List<ExtensionSettingsEntry> = emptyList(),
    val error: String? = null,
)

class ExtensionRuntime(
    private val registry: CapabilityRegistry,
    private val adapter: CapabilityAdapter,
    private val grants: ExtensionGrants,
    private val scope: CoroutineScope,
) {
    private val bundled = registry.snapshot
    private val updates = Mutex()
    private val mutable = MutableStateFlow(ExtensionSettings())
    val settings = mutable.asStateFlow()

    init {
        scope.launch {
            updates.withLock { registry.changeAuthorization { grants.load() } }
            adapter.ready.first { it }
            adapter.installed.collect {
                update { grants.reconcile(adapter.installed.value) }
            }
        }
        adapter.refresh()
    }

    fun refresh() = adapter.refresh()

    fun packageChanged(
        packageName: String,
        removed: Boolean,
    ) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Invalidate before waiting for settings I/O; the final dispatch gate reads this state.
            registry.changeAuthorization { adapter.invalidate(packageName, removed) }
            if (removed) update { grants.reconcile(adapter.installed.value) }
        }
    }

    fun enable(
        key: String,
        enabled: Boolean,
    ) = change(key, enabled) { identity, descriptor -> grants.enable(identity, descriptor, enabled) }

    fun mutation(
        key: String,
        name: String,
        enabled: Boolean,
    ) = change(key, enabled) { identity, descriptor ->
        grants.mutation(identity, descriptor, name, enabled)
    }

    private fun change(
        key: String,
        requireAvailable: Boolean,
        change: suspend (AdapterIdentity, Descriptor) -> Unit,
    ) {
        scope.launch {
            update {
                val entry =
                    adapter.installed.value.find { "${it.identity?.key}:${it.descriptor?.digest}" == key }
                        ?: error("Extension changed")
                val identity = checkNotNull(entry.identity)
                val descriptor = checkNotNull(entry.descriptor)
                if (requireAvailable) require(adapter.available(identity, descriptor.digest))
                change(identity, descriptor)
            }
        }
    }

    private suspend fun update(change: suspend () -> Unit) =
        updates.withLock {
            var error: String? = null
            try {
                registry.changeAuthorization { change() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Extension permissions were not saved. Refresh and try again."
            }
            val installed = adapter.installed.value
            val backends = bundled.bindings.toMutableMap()
            val definitions = bundled.catalog.toMutableList()
            val revisions = bundled.bindingRevisions.toMutableMap()
            for (entry in installed) {
                val identity = entry.identity ?: continue
                val descriptor = entry.descriptor ?: continue
                for (binding in adapter.bindings(entry)) {
                    val capability = binding.capability
                    if (!grants.allowed(identity, descriptor, capability)) continue
                    val backend =
                        GrantedExecutionBackend(binding.backend) {
                            when {
                                !grants.allowed(
                                    identity,
                                    descriptor,
                                    capability,
                                ) -> "Extension permission is disabled. Nothing was executed."

                                !adapter.available(
                                    identity,
                                    descriptor.digest,
                                ) -> "Extension is unavailable or changed. Nothing was executed."

                                else -> null
                            }
                        }
                    backends[binding.definition.id] = backend
                    definitions += binding.definition
                    revisions[binding.definition.id] = binding.revision
                }
            }
            registry.replace(backends, definitions, revisions)
            mutable.value =
                ExtensionSettings(
                    installed.map { entry ->
                        val grant = entry.identity?.let { identity -> entry.descriptor?.let { grants.grant(identity, it) } }
                        ExtensionSettingsEntry(entry, grant != null, grant?.mutations.orEmpty())
                    },
                    error,
                )
        }
}
