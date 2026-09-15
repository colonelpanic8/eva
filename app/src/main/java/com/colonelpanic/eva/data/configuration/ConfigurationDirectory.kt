package com.colonelpanic.eva.data.configuration

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

interface ConfigurationDirectory : ConfigurationReader {
    val label: String

    fun replaceRoot(
        text: String,
        expectedRootFingerprint: String?,
    )
}

class FileConfigurationDirectory(
    root: File,
    override val label: String = root.absolutePath,
) : ConfigurationDirectory {
    private val root = root.canonicalFile

    init {
        require(root.isDirectory || root.mkdirs()) { "Could not create the managed configuration checkout." }
    }

    override fun read(path: String): String? {
        val file = resolve(path)
        if (path == EvaConfigurationCodec.FILE_NAME) recoverRoot()
        if (!file.exists()) return null
        require(file.canonicalFile == file.absoluteFile && file.isFile) { "Configuration path $path is not a regular file." }
        require(file.length() <= EvaConfigurationCodec.MAX_FILE_BYTES) { "Configuration file is too large." }
        return file.readBytes().toString(Charsets.UTF_8)
    }

    override fun replaceRoot(
        text: String,
        expectedRootFingerprint: String?,
    ) {
        EvaConfigurationCodec.decode(text)
        val file = resolve(EvaConfigurationCodec.FILE_NAME)
        val current = read(EvaConfigurationCodec.FILE_NAME)
        require(current?.let(EvaConfigurationCodec::fingerprint) == expectedRootFingerprint) {
            "Configuration changed outside EVA; reload it before editing."
        }
        val temporary = resolve(TEMP_NAME)
        val backup = resolve(BACKUP_NAME)
        require(!temporary.exists() || temporary.delete()) { "Could not clear the previous configuration update." }
        require(!backup.exists() || backup.delete()) { "Could not replace the previous configuration backup." }
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            require(readTemporary(temporary) == text) { "Configuration update could not be verified." }
            require(read(EvaConfigurationCodec.FILE_NAME)?.let(EvaConfigurationCodec::fingerprint) == expectedRootFingerprint) {
                "Configuration changed outside EVA; reload it before editing."
            }
            if (file.exists()) move(file, backup)
            try {
                move(temporary, file)
            } catch (failure: Exception) {
                if (backup.exists()) runCatching { move(backup, file) }
                throw failure
            }
            backup.delete()
        } finally {
            temporary.delete()
        }
    }

    private fun resolve(path: String): File {
        require(path.isNotBlank() && !path.startsWith('/')) { "Configuration path is invalid." }
        val relative = path.split('/')
        require(relative.none { it.isBlank() || it == "." || it == ".." }) { "Configuration path is invalid." }
        var current = root
        relative.forEach { component ->
            current = File(current, component)
            require(!current.exists() || current.canonicalFile == current.absoluteFile) {
                "Configuration path $path contains a symbolic link."
            }
        }
        val canonicalRoot = root.canonicalFile.path.trimEnd(File.separatorChar) + File.separator
        require(current.canonicalFile.path.startsWith(canonicalRoot)) { "Configuration path escapes the managed checkout." }
        return current
    }

    private fun readTemporary(file: File): String {
        require(file.length() <= EvaConfigurationCodec.MAX_FILE_BYTES) { "Configuration file is too large." }
        return file.readBytes().toString(Charsets.UTF_8)
    }

    private fun move(
        source: File,
        destination: File,
    ) {
        require(!destination.exists() || destination.delete()) { "Could not replace ${destination.name}." }
        if (source.renameTo(destination)) return
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
        require(source.delete()) { "Could not finish replacing EVA configuration." }
    }

    private fun recoverRoot() {
        val file = resolve(EvaConfigurationCodec.FILE_NAME)
        val backup = resolve(BACKUP_NAME)
        val temporary = resolve(TEMP_NAME)
        if (backup.isFile && (!file.exists() || temporary.exists())) {
            move(backup, file)
            temporary.delete()
        }
    }

    private companion object {
        const val TEMP_NAME = ".eva.yaml.new"
        const val BACKUP_NAME = ".eva.yaml.backup"
    }
}

class SafConfigurationDirectory(
    context: Context,
    private val tree: Uri,
) : ConfigurationDirectory {
    private val resolver = context.applicationContext.contentResolver
    private val root =
        Document(
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
            query(tree, DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: tree.lastPathSegment ?: "Selected folder",
        )

    override val label: String get() = root.name

    override fun read(path: String): String? {
        val file = find(path) ?: if (path == EvaConfigurationCodec.FILE_NAME) recoverRoot() else null
        return file?.let(::readDocument)
    }

    override fun replaceRoot(
        text: String,
        expectedRootFingerprint: String?,
    ) {
        EvaConfigurationCodec.decode(text)
        val current = find(EvaConfigurationCodec.FILE_NAME) ?: recoverRoot()
        check(current == null || current.name == EvaConfigurationCodec.FILE_NAME) {
            "Could not recover the previous configuration; its backup has been preserved."
        }
        val actualFingerprint = current?.let { EvaConfigurationCodec.fingerprint(readDocument(it)) }
        require(actualFingerprint == expectedRootFingerprint) { "Configuration changed outside EVA; reload it before editing." }
        find(TEMP_NAME)?.let(::delete)
        val temporary =
            DocumentsContract.createDocument(resolver, root.uri, MIME, TEMP_NAME)?.let { Document(it, TEMP_NAME) }
                ?: error("Could not create a configuration update.")
        try {
            writeDocument(temporary.uri, text)
            require(readDocument(temporary) == text) { "Configuration update could not be verified." }
            EvaConfigurationCodec.decode(text)
            val beforeReplace = find(EvaConfigurationCodec.FILE_NAME)?.let { EvaConfigurationCodec.fingerprint(readDocument(it)) }
            require(beforeReplace == expectedRootFingerprint) { "Configuration changed outside EVA; reload it before editing." }
            find(BACKUP_NAME)?.let(::delete)
            if (current !=
                null
            ) {
                require(rename(current, BACKUP_NAME) != null) { "This folder cannot atomically replace EVA configuration." }
            }
            if (rename(temporary, EvaConfigurationCodec.FILE_NAME) == null) {
                find(BACKUP_NAME)?.let { rename(it, EvaConfigurationCodec.FILE_NAME) }
                error("This folder cannot atomically replace EVA configuration.")
            }
            find(BACKUP_NAME)?.let(::delete)
        } catch (error: Exception) {
            delete(temporary)
            throw error
        }
    }

    private fun recoverRoot(): Document? {
        val backup = find(BACKUP_NAME) ?: return null
        rename(backup, EvaConfigurationCodec.FILE_NAME)
        return find(EvaConfigurationCodec.FILE_NAME) ?: backup
    }

    private fun find(path: String): Document? =
        path.split('/').fold(root as Document?) { parent, name ->
            parent?.let { findChild(it, name) }
        }

    private fun findChild(
        parent: Document,
        name: String,
    ): Document? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent.uri))
        queryChildren(children)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                    return Document(uri, name)
                }
            }
        }
        return null
    }

    private fun queryChildren(uri: Uri): Cursor? {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            resolver.query(uri, projection, Bundle.EMPTY, null)
        } else {
            @Suppress("DEPRECATION")
            resolver.query(uri, projection, null, null, null)
        }
    }

    private fun readDocument(file: Document): String =
        resolver.openInputStream(file.uri)?.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= EvaConfigurationCodec.MAX_FILE_BYTES) { "Configuration file is too large." }
                output.write(buffer, 0, count)
            }
            output.toByteArray().toString(Charsets.UTF_8)
        } ?: error("Could not read ${file.name}.")

    private fun rename(
        file: Document,
        name: String,
    ): Document? =
        runCatching { DocumentsContract.renameDocument(resolver, file.uri, name) }
            .getOrNull()
            ?.let { Document(it, name) }

    private fun delete(file: Document): Boolean = runCatching { DocumentsContract.deleteDocument(resolver, file.uri) }.getOrDefault(false)

    private fun query(
        uri: Uri,
        column: String,
    ): String? =
        runCatching {
            resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                cursor.takeIf(Cursor::moveToFirst)?.getString(0)
            }
        }.getOrNull()

    private fun writeDocument(
        uri: Uri,
        text: String,
    ) {
        val descriptor = requireNotNull(resolver.openFileDescriptor(uri, "wt")) { "Could not write configuration update." }
        descriptor.use {
            FileOutputStream(it.fileDescriptor).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
        }
    }

    companion object {
        const val MIME = "application/x-yaml"
        private const val TEMP_NAME = ".eva.yaml.new"
        private const val BACKUP_NAME = ".eva.yaml.backup"

        fun persistAccess(
            resolver: ContentResolver,
            uri: Uri,
        ) {
            resolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    private data class Document(
        val uri: Uri,
        val name: String,
    )
}
