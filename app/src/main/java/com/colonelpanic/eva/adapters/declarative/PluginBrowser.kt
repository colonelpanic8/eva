package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.extensions.rethrowFatalExtensionFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

data class PluginBrowserState(
    val source: String = DEFAULT_PLUGIN_INDEX,
    val listings: List<PluginListing> = emptyList(),
    val installed: List<InstalledPlugin> = emptyList(),
    val visibleApps: Set<String> = emptySet(),
    val preview: PluginPreview? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

const val DEFAULT_PLUGIN_INDEX = "https://raw.githubusercontent.com/colonelpanic8/eva-extensions/main/index.json"

class PluginBrowser(
    private val repository: PluginRepository,
    private val scope: CoroutineScope,
    source: String,
    private val installed: () -> List<InstalledPlugin>,
    private val visibleApps: () -> Set<String>,
    private val saveSource: (String) -> Unit,
    private val install: suspend (PluginPreview) -> Unit,
    private val remove: suspend (String) -> Unit,
) {
    private val mutable = MutableStateFlow(PluginBrowserState(source = source, installed = installed()))
    val state = mutable.asStateFlow()
    private val work = Mutex()

    fun refresh(source: String) =
        run {
            mutable.value = mutable.value.copy(preview = null)
            val normalized = PluginRepository.repositoryUrl(source).toString()
            val listings = repository.list(normalized)
            saveSource(normalized)
            mutable.value = PluginBrowserState(normalized, listings, installed(), visibleApps(), busy = true)
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
            install(preview)
            mutable.value =
                mutable.value.copy(
                    preview = null,
                    installed = installed(),
                    notice = "Installed disabled. Enable its actions below, then reconnect.",
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
