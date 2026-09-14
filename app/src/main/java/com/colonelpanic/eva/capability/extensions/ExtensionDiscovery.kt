package com.colonelpanic.eva.capability.extensions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class InstalledExtension(
    val packageName: String,
    val identity: AdapterIdentity?,
    val descriptor: Descriptor?,
    val problem: String? = null,
    val contractRejected: Boolean = false,
    val capabilityPrefix: String = "extension.$packageName",
)

class ExtensionDiscovery(
    private val scan: suspend () -> List<ExtensionCandidate>,
    private val connections: ExtensionConnectionManager,
    scope: CoroutineScope,
    private val report: (Throwable) -> Unit = {},
) {
    private val monitor = Any()
    private var generation = 0L
    private val refreshes = Channel<Unit>(Channel.CONFLATED)
    private val mutable = MutableStateFlow<List<InstalledExtension>>(emptyList())
    val installed = mutable.asStateFlow()
    private val initialized = MutableStateFlow(false)
    val ready = initialized.asStateFlow()

    init {
        scope.launch {
            for (signal in refreshes) {
                delay(250)
                while (refreshes.tryReceive().isSuccess) { /* Coalesce package bursts. */ }
                try {
                    refresh()
                } catch (failure: Throwable) {
                    rethrowFatalExtensionFailure(failure)
                    report(failure)
                    synchronized(monitor) {
                        mutable.value =
                            mutable.value
                                .map { it.copy(problem = "Extension discovery is temporarily unavailable.") }
                                .ifEmpty {
                                    listOf(
                                        InstalledExtension(
                                            "Installed extension apps",
                                            null,
                                            null,
                                            "Extension discovery failed. Refresh to retry.",
                                        ),
                                    )
                                }
                        initialized.value = true
                    }
                }
            }
        }
    }

    fun requestRefresh() {
        refreshes.trySend(Unit)
    }

    fun invalidate(
        packageName: String,
        removed: Boolean,
    ) = synchronized(monitor) {
        generation++
        mutable.value =
            if (removed) {
                mutable.value.filterNot { it.packageName == packageName }
            } else {
                mutable.value.map {
                    if (it.packageName == packageName) it.copy(problem = "Package changed; awaiting extension validation.") else it
                }
            }
        requestRefresh()
    }

    fun available(
        identity: ExtensionIdentity,
        digest: String,
    ): Boolean = installed.value.any { it.identity == identity && it.descriptor?.digest == digest && it.problem == null }

    private suspend fun refresh() {
        val (version, previous) = synchronized(monitor) { generation to mutable.value }
        val candidates = scan()
        val permits = Semaphore(4)
        val entries =
            coroutineScope {
                selectExtensions(candidates)
                    .map { listing ->
                        async {
                            permits.withPermit {
                                val identity = listing.identity
                                if (identity == null) return@withPermit InstalledExtension(listing.packageName, null, null, listing.problem)
                                val old = previous.find { it.identity == identity }
                                when (val reply = connections.describe(identity)) {
                                    is ExtensionExchange.Reply -> {
                                        val description = runCatching { ExtensionProtocol.describe(reply.json) }.getOrNull()
                                        InstalledExtension(
                                            listing.packageName,
                                            identity,
                                            description?.descriptor ?: old?.descriptor?.takeIf { description != null },
                                            if (description?.descriptor ==
                                                null
                                            ) {
                                                "Extension description was rejected or unavailable."
                                            } else {
                                                null
                                            },
                                            contractRejected = description == null,
                                        )
                                    }

                                    else -> {
                                        InstalledExtension(
                                            listing.packageName,
                                            identity,
                                            old?.descriptor,
                                            "Extension is temporarily unreachable. Try reopening EVA.",
                                        )
                                    }
                                }
                            }
                        }
                    }.awaitAll()
            }
        synchronized(monitor) {
            if (version == generation) {
                mutable.value = entries
                initialized.value = true
            }
        }
    }
}
