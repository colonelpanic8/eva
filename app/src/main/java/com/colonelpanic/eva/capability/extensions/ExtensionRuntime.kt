package com.colonelpanic.eva.capability.extensions

import com.colonelpanic.eva.capability.CapabilityRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
    private val onChanged: () -> Unit = {},
    private val onGrantChanged: (String) -> Unit = {},
) {
    private val initialized = CompletableDeferred<Unit>()
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
                initialized.complete(Unit)
            }
        }
        adapter.refresh()
    }

    suspend fun awaitReady() = initialized.await()

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

    fun enableAll(key: String) = change(key, true) { identity, descriptor -> grants.enableAll(identity, descriptor) }

    fun mutation(
        key: String,
        name: String,
        enabled: Boolean,
    ) = change(key, enabled) { identity, descriptor ->
        grants.mutation(identity, descriptor, name, enabled)
    }

    fun portableGrants(): Map<String, ExtensionGrant> = grants.all()

    /** Approves every action of a freshly installed default package once its descriptor is live. */
    suspend fun adopt(identity: AdapterIdentity): Boolean {
        adapter.refresh()
        withTimeoutOrNull(5_000) {
            adapter.ready.first { it }
            adapter.installed.first { installed ->
                installed.any { it.identity?.instanceId == identity.instanceId && it.descriptor != null }
            }
        }
        var adopted = false
        update {
            val entry = adapter.installed.value.find { it.identity?.instanceId == identity.instanceId } ?: return@update
            val descriptor = entry.descriptor ?: return@update
            grants.enableAll(checkNotNull(entry.identity), descriptor)
            adopted = true
        }
        if (adopted) onGrantChanged(identity.instanceId)
        return adopted
    }

    /** Carries an approved extension's grants onto the version a catalog refresh just installed. */
    suspend fun carryForward(
        identity: AdapterIdentity,
        previousDigest: String,
        previousActions: Set<String>,
        enableNewActions: Boolean,
    ): Boolean {
        adapter.refresh()
        withTimeoutOrNull(5_000) {
            adapter.ready.first { it }
            adapter.installed.first { installed ->
                installed.any { it.identity?.instanceId == identity.instanceId && it.descriptor != null }
            }
        }
        var carried = false
        update {
            val entry = adapter.installed.value.find { it.identity?.instanceId == identity.instanceId } ?: return@update
            val descriptor = entry.descriptor ?: return@update
            carried = grants.rebind(checkNotNull(entry.identity), descriptor, previousDigest, previousActions, enableNewActions)
        }
        if (carried) onGrantChanged(identity.instanceId)
        return carried
    }

    suspend fun restoreGrants(
        restored: Map<String, ExtensionGrant>,
        expectedPackageInstances: Set<String>,
    ): Set<String> {
        adapter.refresh()
        withTimeoutOrNull(5_000) {
            adapter.ready.first { it }
            adapter.installed.first { installed ->
                val live = installed.mapNotNull { it.identity?.instanceId }.toSet()
                expectedPackageInstances.all { "package:$it" in live }
            }
        }
        var missing = emptySet<String>()
        update { missing = grants.restore(restored, adapter.installed.value) }
        return missing
    }

    private fun change(
        key: String,
        requireAvailable: Boolean,
        change: suspend (AdapterIdentity, Descriptor) -> Unit,
    ) {
        scope.launch {
            var changedInstance: String? = null
            update {
                val entry =
                    adapter.installed.value.find { "${it.identity?.key}:${it.descriptor?.digest}" == key }
                        ?: error("Extension changed")
                val identity = checkNotNull(entry.identity)
                val descriptor = checkNotNull(entry.descriptor)
                if (requireAvailable) require(adapter.available(identity, descriptor.digest))
                change(identity, descriptor)
                changedInstance = identity.instanceId
            }
            changedInstance?.let(onGrantChanged)
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
            onChanged()
        }
}
