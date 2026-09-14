package com.colonelpanic.eva.capability.extensions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** An adapter fault disables that adapter until restart, without granting or retaining execution. */
class IsolatedCapabilityAdapter(
    private val name: String,
    scope: CoroutineScope,
    private val report: (Throwable) -> Unit,
    create: () -> CapabilityAdapter,
) : CapabilityAdapter {
    override val installed = MutableStateFlow<List<InstalledExtension>>(emptyList())
    override val ready = MutableStateFlow(false)

    @Volatile private var failed = false
    private val delegate = attempt<CapabilityAdapter?>(null, create)

    init {
        delegate?.let { adapter ->
            scope.launch {
                try {
                    combine(adapter.ready, adapter.installed) { ready, entries -> ready to entries }
                        .collect { (initialized, entries) ->
                            if (!failed) {
                                installed.value = entries
                                ready.value = initialized
                            }
                        }
                } catch (failure: Throwable) {
                    fail(failure)
                }
            }
        }
    }

    private fun fail(failure: Throwable) {
        rethrowFatalExtensionFailure(failure)
        failed = true
        report(failure)
        installed.value =
            installed.value
                .map { it.copy(problem = PROBLEM) }
                .ifEmpty { listOf(InstalledExtension(name, null, null, PROBLEM)) }
        ready.value = true
    }

    private fun <T> attempt(
        fallback: T,
        block: () -> T,
    ): T =
        if (failed) {
            fallback
        } else {
            try {
                block()
            } catch (failure: Throwable) {
                fail(failure)
                fallback
            }
        }

    override fun refresh() =
        attempt(Unit) {
            delegate?.refresh()
            Unit
        }

    override fun invalidate(
        packageName: String,
        removed: Boolean,
    ) = attempt(Unit) {
        delegate?.invalidate(packageName, removed)
        Unit
    }

    override fun available(
        identity: AdapterIdentity,
        digest: String,
    ): Boolean = attempt(false) { delegate?.available(identity, digest) == true }

    override fun bindings(extension: InstalledExtension): List<CapabilityBinding> =
        attempt(emptyList()) { delegate?.bindings(extension).orEmpty() }

    companion object {
        private const val PROBLEM = "Extension adapter failed to load. Restart EVA after updating or removing the affected package."
    }
}

fun rethrowFatalExtensionFailure(failure: Throwable) {
    if (failure is CancellationException || failure is VirtualMachineError || failure is ThreadDeath) throw failure
}
