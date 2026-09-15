package com.colonelpanic.eva.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import com.colonelpanic.eva.conversation.prompt.PromptConfig
import com.colonelpanic.eva.conversation.prompt.PromptConfigException
import com.colonelpanic.eva.conversation.prompt.PromptDefaults
import com.colonelpanic.eva.conversation.prompt.PromptYaml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

sealed interface PromptState {
    data object Loading : PromptState

    data class Loaded(
        val config: PromptConfig,
    ) : PromptState

    data class Failed(
        val message: String,
    ) : PromptState
}

/** Where the prompt file is: EVA's own copy, or a document the user picked. */
data class PromptLocation(
    val name: String,
    val chosen: Boolean,
)

data class PromptPersistenceSnapshot(
    val source: String,
    val config: PromptConfig,
    val document: String?,
    val documentName: String?,
)

/**
 * The prompt file on disk. EVA's own copy sits in the app's external files directory, which
 * `adb` and a USB connection can reach without any permission. A file the user picks through
 * the system picker replaces it, with the persisted grant those pickers hand out, so the
 * prompt can live in a synced folder or a checkout. Either way the file is read again for
 * every session and written only when something changes here, and it is never rewritten
 * unless it has proved readable first.
 */
class PromptStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val repository: PromptRepository = PromptRepository(),
    private val onChanged: () -> Unit = {},
) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("eva.prompt", Context.MODE_PRIVATE)
    private val ownFile = File(this.context.getExternalFilesDir(null) ?: this.context.filesDir, PromptYaml.FILE_NAME)
    private val mutableState = MutableStateFlow<PromptState>(PromptState.Loading)
    val state = mutableState.asStateFlow()
    private val mutableLocation = MutableStateFlow(currentLocation())
    val location = mutableLocation.asStateFlow()
    private val mutableNotice = MutableStateFlow<String?>(null)
    private val mutableNoticeIsError = MutableStateFlow(false)
    private val mutableSource = MutableStateFlow(prefs.getString(SOURCE, null) ?: PromptRepository.DEFAULT_SOURCE)
    val source = mutableSource.asStateFlow()
    private val mutableRefreshing = MutableStateFlow(false)
    val refreshing = mutableRefreshing.asStateFlow()

    /** Result of the last user action, until the next one does. */
    val notice = mutableNotice.asStateFlow()
    val noticeIsError = mutableNoticeIsError.asStateFlow()

    /** The file as it is now. An absent or empty own copy is first given the defaults. */
    suspend fun load(): PromptConfig =
        withContext(ioDispatcher) {
            try {
                val text = read()
                val config =
                    if (text.isBlank()) {
                        write(PromptYaml.encode(PromptDefaults.config))
                        PromptDefaults.config
                    } else {
                        val parsed = PromptYaml.decode(text).validated(PromptDefaults.VARIABLES)
                        PromptDefaults.upgradeStockCallWording(parsed).also { upgraded ->
                            if (upgraded != parsed) {
                                write(PromptYaml.encode(upgraded))
                                onChanged()
                            }
                        }
                    }
                mutableState.value = PromptState.Loaded(config)
                config
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = error.message ?: "The prompt file could not be read."
                mutableState.value = PromptState.Failed(message)
                throw PromptConfigException(message, error)
            }
        }

    /** Same as [load], for a screen that shows the failure rather than needing the value. */
    suspend fun reload() {
        try {
            load()
        } catch (error: PromptConfigException) {
            // Already recorded in state.
        }
    }

    suspend fun update(change: (PromptConfig) -> PromptConfig) =
        attempt {
            val current = (state.value as? PromptState.Loaded)?.config ?: load()
            save(change(current))
        }

    suspend fun resetToDefaults() = attempt { save(PromptDefaults.config) }

    /** Replaces the catalog from a raw HTTPS file while retaining switches for matching ids. */
    suspend fun refreshFrom(source: String) {
        if (mutableRefreshing.value) return
        mutableRefreshing.value = true
        try {
            val current = (state.value as? PromptState.Loaded)?.config ?: load()
            val remote = withContext(ioDispatcher) { repository.load(source) }
            save(mergePromptUpdate(current, remote.config))
            prefs.edit { putString(SOURCE, remote.source) }
            mutableSource.value = remote.source
            onChanged()
            mutableNotice.value = "Instructions updated. Changes apply to the next session."
            mutableNoticeIsError.value = false
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableNotice.value = error.message ?: "The instruction source could not be updated."
            mutableNoticeIsError.value = true
        } finally {
            mutableRefreshing.value = false
        }
    }

    /**
     * Adopts a picked document. A new one receives the current prompt; an existing one has to
     * parse before it replaces anything, and an empty one is treated as new.
     */
    suspend fun useDocument(
        uri: Uri,
        create: Boolean,
    ) = attempt {
        val resolver = context.contentResolver
        try {
            resolver.takePersistableUriPermission(uri, GRANT_FLAGS)
        } catch (error: SecurityException) {
            throw PromptConfigException("EVA was not given lasting access to that file.", error)
        }
        try {
            withContext(ioDispatcher) {
                val text = if (create) "" else readFrom(uri)
                if (text.isBlank()) {
                    writeTo(uri, PromptYaml.encode(currentOrDefaults()))
                } else {
                    PromptYaml.decode(text).validated(PromptDefaults.VARIABLES)
                }
            }
        } catch (error: Exception) {
            if (error !is CancellationException) runCatching { resolver.releasePersistableUriPermission(uri, GRANT_FLAGS) }
            throw error
        }
        prefs.edit {
            putString(DOCUMENT, uri.toString())
            putString(DOCUMENT_NAME, displayName(uri))
        }
        mutableLocation.value = currentLocation()
        load()
        onChanged()
    }

    suspend fun useOwnFile() =
        attempt {
            documentUri()?.let { runCatching { context.contentResolver.releasePersistableUriPermission(it, GRANT_FLAGS) } }
            prefs.edit {
                remove(DOCUMENT)
                remove(DOCUMENT_NAME)
            }
            mutableLocation.value = currentLocation()
            load()
            onChanged()
        }

    fun clearNotice() {
        mutableNotice.value = null
        mutableNoticeIsError.value = false
    }

    private suspend fun save(config: PromptConfig) {
        config.validated(PromptDefaults.VARIABLES)
        withContext(ioDispatcher) { write(PromptYaml.encode(config)) }
        mutableState.value = PromptState.Loaded(config)
        onChanged()
    }

    suspend fun portableSnapshot(): PromptPersistenceSnapshot =
        PromptPersistenceSnapshot(mutableSource.value, load(), prefs.getString(DOCUMENT, null), prefs.getString(DOCUMENT_NAME, null))

    suspend fun restorePortable(
        source: String,
        config: PromptConfig,
    ) {
        config.validated(PromptDefaults.VARIABLES)
        withContext(ioDispatcher) {
            ownFile.parentFile?.mkdirs()
            ownFile.writeText(PromptYaml.encode(config))
        }
        commitPreferences {
            putString(SOURCE, source)
            remove(DOCUMENT)
            remove(DOCUMENT_NAME)
        }
        mutableSource.value = source
        mutableLocation.value = currentLocation()
        mutableState.value = PromptState.Loaded(config)
    }

    suspend fun rollback(snapshot: PromptPersistenceSnapshot) {
        snapshot.config.validated(PromptDefaults.VARIABLES)
        if (snapshot.document == null) {
            withContext(ioDispatcher) {
                ownFile.parentFile?.mkdirs()
                ownFile.writeText(PromptYaml.encode(snapshot.config))
            }
        }
        commitPreferences {
            putString(SOURCE, snapshot.source)
            if (snapshot.document == null) {
                remove(DOCUMENT)
                remove(DOCUMENT_NAME)
            } else {
                putString(DOCUMENT, snapshot.document)
                snapshot.documentName?.let { putString(DOCUMENT_NAME, it) } ?: remove(DOCUMENT_NAME)
            }
        }
        mutableSource.value = snapshot.source
        mutableLocation.value = currentLocation()
        mutableState.value = PromptState.Loaded(snapshot.config)
    }

    @SuppressLint("UseKtx")
    private fun commitPreferences(change: android.content.SharedPreferences.Editor.() -> Unit) {
        check(prefs.edit().apply(change).commit()) { "Could not save prompt settings." }
    }

    private suspend fun currentOrDefaults(): PromptConfig =
        (state.value as? PromptState.Loaded)?.config
            ?: try {
                load()
            } catch (_: PromptConfigException) {
                PromptDefaults.config
            }

    /** User actions report through [notice] rather than failing the caller. */
    private suspend fun attempt(action: suspend () -> Unit) {
        try {
            action()
            mutableNotice.value = null
            mutableNoticeIsError.value = false
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableNotice.value = error.message ?: "That did not work."
            mutableNoticeIsError.value = true
        }
    }

    private fun documentUri(): Uri? = prefs.getString(DOCUMENT, null)?.let(Uri::parse)

    private fun currentLocation(): PromptLocation =
        documentUri()?.let { uri ->
            PromptLocation(prefs.getString(DOCUMENT_NAME, null) ?: uri.lastPathSegment ?: "Chosen file", chosen = true)
        } ?: PromptLocation(ownFile.absolutePath, chosen = false)

    private fun read(): String = documentUri()?.let(::readFrom) ?: if (ownFile.exists()) ownFile.readText() else ""

    private fun write(text: String) {
        // A file under version control should not be touched when nothing about it changed.
        if (runCatching { read() }.getOrNull() == text) return
        val uri = documentUri()
        if (uri != null) {
            writeTo(uri, text)
        } else {
            ownFile.parentFile?.mkdirs()
            ownFile.writeText(text)
        }
    }

    private fun readFrom(uri: Uri): String =
        context.contentResolver
            .openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: throw PromptConfigException("The chosen prompt file could not be opened.")

    /** "wt" because some providers append rather than truncate on a plain "w". */
    private fun writeTo(
        uri: Uri,
        text: String,
    ) {
        context.contentResolver
            .openOutputStream(uri, "wt")
            ?.bufferedWriter()
            ?.use { it.write(text) }
            ?: throw PromptConfigException("The chosen prompt file could not be written.")
    }

    private fun displayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

    private companion object {
        const val DOCUMENT = "prompt.document"
        const val DOCUMENT_NAME = "prompt.documentName"
        const val SOURCE = "prompt.source"
        const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

/** A repository owns its component text and order; the phone owns existing switch choices. */
internal fun mergePromptUpdate(
    current: PromptConfig,
    remote: PromptConfig,
): PromptConfig {
    val enabled = current.components.associate { it.id to it.enabled }
    return remote.copy(
        components =
            remote.components.map { component ->
                enabled[component.id]?.let { component.copy(enabled = it) } ?: component
            },
    )
}
