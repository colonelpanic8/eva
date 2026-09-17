package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.extensions.rethrowFatalExtensionFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

data class PluginBrowserState(
    val source: String = DEFAULT_PLUGIN_REPOSITORY,
    val listings: List<PluginListing> = emptyList(),
    val installed: List<InstalledPlugin> = emptyList(),
    val visibleApps: Set<String> = emptySet(),
    val preview: PluginPreview? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val checked: Boolean = false,
) {
    /**
     * Installed extensions whose catalog listing carries a higher version. Derived rather than
     * stored so a refresh or an install cannot leave a stale update offer on screen.
     */
    val updates: List<PluginUpdate> get() =
        listings.mapNotNull { listing ->
            installed
                .find { it.source == source && it.definition.id == listing.id }
                ?.takeIf { PackageVersion.newer(listing.version, it.definition.version) }
                ?.let { PluginUpdate(listing.id, listing.title, it.definition.version, listing.version) }
        }
}

/** One installed extension the catalog offers a newer version of. */
data class PluginUpdate(
    val id: String,
    val title: String,
    val installedVersion: String,
    val availableVersion: String,
)

const val DEFAULT_PLUGIN_REPOSITORY = "https://github.com/colonelpanic8/eva-extensions.git"

/** How long a catalog listing stands in for a fresh one when the extensions screen opens. */
const val CHECK_INTERVAL_MILLIS = 15 * 60 * 1000L

class PluginBrowser(
    private val repository: PluginRepository,
    private val scope: CoroutineScope,
    source: String,
    private val installed: () -> List<InstalledPlugin>,
    private val visibleApps: () -> Set<String>,
    private val saveSource: (String) -> Unit,
    private val install: suspend (PluginPreview) -> Unit,
    private val remove: suspend (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastListed: Long? = null
    private val mutable = MutableStateFlow(PluginBrowserState(source = source, installed = installed()))
    val state = mutable.asStateFlow()
    private val work = Mutex()

    fun refresh() = refresh(mutable.value.source)

    /**
     * Lists the catalog when the extensions screen opens, so an available update is visible
     * without the user thinking to ask. A recent listing, or work already running, is left alone.
     */
    fun checkForUpdates() {
        val current = mutable.value
        if (current.busy) return
        val last = lastListed
        if (last != null && clock() - last < CHECK_INTERVAL_MILLIS) return
        refresh(current.source)
    }

    fun refresh(source: String) =
        run {
            mutable.value = mutable.value.copy(preview = null)
            val normalized = repository.source(source)
            val catalog = repository.list(normalized)
            saveSource(normalized)
            lastListed = clock()
            mutable.value =
                PluginBrowserState(
                    normalized,
                    catalog.listings,
                    installed(),
                    visibleApps(),
                    busy = true,
                    notice = catalog.problems.takeIf { it.isNotEmpty() }?.joinToString("\n") { "Skipped $it" },
                    checked = true,
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

    fun preview(id: String) =
        run {
            mutable.value = mutable.value.copy(preview = null)
            val current = mutable.value
            val listing = current.listings.single { it.id == id }
            mutable.value = current.copy(preview = repository.preview(current.source, listing), error = null)
        }

    fun installPreview() =
        run {
            val preview = requireNotNull(mutable.value.preview) { "Preview an extension first" }
            val source = PluginRepository.installationSource(preview.source)
            val replaced = mutable.value.installed.find { it.source == source && it.definition.id == preview.definition.id }
            install(preview)
            mutable.value =
                mutable.value.copy(
                    preview = null,
                    installed = installed(),
                    notice =
                        if (replaced == null) {
                            "Installed disabled. Enable its actions below, then reconnect."
                        } else {
                            "Updated to ${preview.definition.version}. A changed extension needs its actions enabled again, then reconnect."
                        },
                )
        }

    fun remove(instance: String) =
        run {
            remove.invoke(instance)
            mutable.value = mutable.value.copy(installed = installed(), notice = "Extension removed. Its Android app is unchanged.")
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
                mutable.value = mutable.value.copy(busy = false)
                work.unlock()
            }
        }
    }
}
