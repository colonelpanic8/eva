package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.extensions.rethrowFatalExtensionFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** One followed catalog and what its last refresh found. */
data class RepositoryState(
    val source: String,
    val listings: List<PluginListing> = emptyList(),
    /** Files under `packages/` that did not decode, and packages this refresh could not install. */
    val problems: List<String> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val refreshed: Boolean = false,
)

data class PluginBrowserState(
    val repositories: List<RepositoryState> = emptyList(),
    val installed: List<InstalledPlugin> = emptyList(),
    val visibleApps: Set<String> = emptySet(),
    /** Only a one-off URL or file import is previewed; a followed catalog installs its own packages. */
    val preview: PluginPreview? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

const val DEFAULT_PLUGIN_REPOSITORY = "https://github.com/colonelpanic8/eva-extensions.git"

/** What a refresh did to one catalog package, so the screen can say so without guessing. */
enum class SyncOutcome { ADDED, UPDATED, UNCHANGED }

/** Applies one catalog package: installs it if it is new, replaces it if its content changed. */
fun interface PluginSync {
    suspend fun apply(preview: PluginPreview): SyncOutcome
}

/**
 * Follows the configured catalogs. A repository the user adds is trusted: refreshing it installs
 * everything it publishes and updates what changed, without a review step for each package.
 * Enabling is a separate decision that persists across refreshes.
 */
class PluginBrowser(
    private val repository: PluginRepository,
    private val scope: CoroutineScope,
    private val sources: () -> List<String>,
    private val installed: () -> List<InstalledPlugin>,
    private val visibleApps: () -> Set<String>,
    private val saveSources: (List<String>) -> Unit,
    private val sync: PluginSync,
    private val install: suspend (PluginPreview) -> Unit,
    private val remove: suspend (String) -> Unit,
) {
    private val mutable =
        MutableStateFlow(
            PluginBrowserState(sources().map(::RepositoryState), installed()),
        )
    val state = mutable.asStateFlow()
    private val work = Mutex()

    /** Refreshes every followed catalog, as when the extensions screen opens. */
    fun refreshAll() = run { sources().forEach { synchronize(it) } }

    fun refresh(source: String) = run { synchronize(source) }

    /** Refreshes catalogs this run has not listed yet, so opening the screen shows what they publish. */
    fun refreshNew() =
        run {
            sources()
                .filter { source -> mutable.value.repositories.none { it.source == source && it.refreshed } }
                .forEach { synchronize(it) }
        }

    fun addRepository(value: String) =
        run {
            val normalized = repository.source(value)
            val existing = sources()
            require(normalized !in existing) { "That repository is already followed." }
            saveSources(existing + normalized)
            mutable.value = mutable.value.copy(repositories = mutable.value.repositories + RepositoryState(normalized))
            synchronize(normalized)
        }

    /** Stops following a catalog. Extensions installed from it stay installed until removed. */
    fun removeRepository(source: String) =
        run {
            saveSources(sources().filterNot { it == source })
            mutable.value =
                mutable.value.copy(
                    repositories = mutable.value.repositories.filterNot { it.source == source },
                    notice = "No longer following $source. Extensions it installed stay until you remove them.",
                )
        }

    fun previewUrl(url: String) =
        run {
            mutable.value = mutable.value.copy(preview = null)
            mutable.value = mutable.value.copy(preview = repository.previewUrl(url))
        }

    fun previewFile(open: () -> java.io.InputStream) =
        run {
            mutable.value = mutable.value.copy(preview = null)
            mutable.value = mutable.value.copy(preview = PluginRepository.filePreview(open()))
        }

    fun installPreview() =
        run {
            val preview = requireNotNull(mutable.value.preview) { "Preview an extension first" }
            install(preview)
            mutable.value =
                mutable.value.copy(
                    preview = null,
                    installed = installed(),
                    notice = "Imported ${preview.definition.title}. Enable its actions below, then reconnect.",
                )
        }

    fun remove(instance: String) =
        run {
            remove.invoke(instance)
            mutable.value = mutable.value.copy(installed = installed(), notice = "Extension removed. Its Android app is unchanged.")
        }

    private suspend fun synchronize(source: String) {
        update(source) { it.copy(busy = true, error = null) }
        runCatching {
            val catalog = repository.list(source)
            val problems = catalog.problems.toMutableList()
            val outcomes =
                catalog.listings.map { listing ->
                    runCatching { sync.apply(repository.preview(source, listing)) }
                        .onFailure { failure ->
                            rethrowFatalExtensionFailure(failure)
                            problems += "${listing.id}: ${failure.message ?: "could not be installed"}"
                        }.getOrNull()
                }
            val added = outcomes.count { it == SyncOutcome.ADDED }
            val updated = outcomes.count { it == SyncOutcome.UPDATED }
            update(source) {
                it.copy(listings = catalog.listings, problems = problems, busy = false, error = null, refreshed = true)
            }
            mutable.value =
                mutable.value.copy(
                    installed = installed(),
                    visibleApps = visibleApps(),
                    notice =
                        listOfNotNull("$added added".takeIf { added > 0 }, "$updated updated".takeIf { updated > 0 })
                            .takeIf { it.isNotEmpty() }
                            ?.joinToString(", ")
                            ?.let { "Extensions $it. Reconnect to use changed actions." }
                            ?: mutable.value.notice,
                )
        }.onFailure { failure ->
            rethrowFatalExtensionFailure(failure)
            update(source) { it.copy(busy = false, error = failure.message ?: "The repository could not be refreshed") }
        }
    }

    private fun update(
        source: String,
        change: (RepositoryState) -> RepositoryState,
    ) {
        val current = mutable.value.repositories
        val next =
            if (current.none { it.source == source }) {
                current + change(RepositoryState(source))
            } else {
                current.map { if (it.source == source) change(it) else it }
            }
        mutable.value = mutable.value.copy(repositories = next)
    }

    private fun run(block: suspend () -> Unit) {
        if (!work.tryLock()) return
        mutable.value = mutable.value.copy(busy = true, error = null, notice = null)
        scope.launch {
            try {
                block()
            } catch (failure: Throwable) {
                rethrowFatalExtensionFailure(failure)
                mutable.value = mutable.value.copy(error = failure.message ?: "Repository operation failed")
            } finally {
                mutable.value = mutable.value.copy(busy = false, installed = installed())
                work.unlock()
            }
        }
    }
}
