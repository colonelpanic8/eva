package com.colonelpanic.eva.data.configuration

import android.Manifest
import android.app.Application
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], application = Application::class, manifest = Config.NONE)
class ConfigurationDirectoryTest {
    private lateinit var root: File
    private lateinit var directory: SafConfigurationDirectory
    private lateinit var provider: ConfigurationDocumentsProvider

    @Before
    fun setUp() {
        root = Files.createTempDirectory("eva-configuration-directory").toFile()
        val application: Application = RuntimeEnvironment.getApplication()
        val providerInfo =
            ProviderInfo().apply {
                authority = AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = Manifest.permission.MANAGE_DOCUMENTS
                writePermission = Manifest.permission.MANAGE_DOCUMENTS
                applicationInfo = application.applicationInfo
                name = ConfigurationDocumentsProvider::class.java.name
                packageName = application.packageName
            }
        provider = Robolectric.buildContentProvider(ConfigurationDocumentsProvider::class.java).create(providerInfo).get()
        provider.root = root
        directory =
            SafConfigurationDirectory(
                application,
                DocumentsContract.buildTreeDocumentUri(AUTHORITY, ConfigurationDocumentsProvider.ROOT_ID),
            )
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `SAF read refuses an oversized stream before returning content`() {
        File(root, EvaConfigurationCodec.FILE_NAME).writeBytes(ByteArray(EvaConfigurationCodec.MAX_FILE_BYTES + 1) { 'x'.code.toByte() })

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                directory.read(EvaConfigurationCodec.FILE_NAME)
            }

        assertTrue(failure.message.orEmpty().contains("too large"))
    }

    @Test
    fun `reading a backup-only interrupted state recovers the old root`() {
        val old = document(lookupRetries = 2)
        File(root, BACKUP_NAME).writeText(old)

        assertEquals(old, directory.read(EvaConfigurationCodec.FILE_NAME))
        assertEquals(old, File(root, EvaConfigurationCodec.FILE_NAME).readText())
        assertFalse(File(root, BACKUP_NAME).exists())
    }

    @Test
    fun `replacement recovers backup before cleanup and restores it when final rename fails`() {
        val old = document(lookupRetries = 2)
        val replacement = document(lookupRetries = 8)
        File(root, BACKUP_NAME).writeText(old)
        provider.failFinalRename = true

        val failure =
            assertThrows(IllegalStateException::class.java) {
                directory.replaceRoot(replacement, EvaConfigurationCodec.fingerprint(old))
            }

        assertNotNull(failure.message)
        assertEquals(old, directory.read(EvaConfigurationCodec.FILE_NAME))
        assertEquals(old, File(root, EvaConfigurationCodec.FILE_NAME).readText())
        assertFalse(File(root, BACKUP_NAME).exists())
        assertFalse(File(root, TEMP_NAME).exists())
    }

    private fun document(lookupRetries: Int): String =
        EvaConfigurationCodec.encode(
            EvaConfigurationDocument(voice = VoicePatch(lookupRetries = lookupRetries)),
        )

    private companion object {
        const val AUTHORITY = "com.colonelpanic.eva.test.documents"
        const val BACKUP_NAME = ".eva.yaml.backup"
        const val TEMP_NAME = ".eva.yaml.new"
    }
}

class ConfigurationDocumentsProvider : DocumentsProvider() {
    lateinit var root: File
    var failFinalRename = false

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: ROOT_COLUMNS
        return MatrixCursor(columns).apply {
            addRow(
                columns,
                mapOf(
                    DocumentsContract.Root.COLUMN_ROOT_ID to ROOT_ID,
                    DocumentsContract.Root.COLUMN_DOCUMENT_ID to ROOT_ID,
                    DocumentsContract.Root.COLUMN_TITLE to "EVA configuration test",
                    DocumentsContract.Root.COLUMN_FLAGS to DocumentsContract.Root.FLAG_LOCAL_ONLY,
                ),
            )
        }
    }

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?,
    ): Cursor {
        val columns = projection ?: DOCUMENT_COLUMNS
        val file = file(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        return MatrixCursor(columns).apply { addDocumentRow(columns, documentId, file) }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        if (parentDocumentId != ROOT_ID) throw FileNotFoundException(parentDocumentId)
        val columns = projection ?: DOCUMENT_COLUMNS
        return MatrixCursor(columns).apply {
            root.listFiles().orEmpty().sortedBy(File::getName).forEach { child ->
                addDocumentRow(columns, id(child), child)
            }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = file(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        if (parentDocumentId != ROOT_ID) throw FileNotFoundException(parentDocumentId)
        val target = File(root, displayName)
        if (!target.createNewFile()) throw FileNotFoundException("$displayName already exists")
        return id(target)
    }

    override fun renameDocument(
        documentId: String,
        displayName: String,
    ): String {
        val source = file(documentId)
        if (failFinalRename && source.name == ".eva.yaml.new" && displayName == EvaConfigurationCodec.FILE_NAME) {
            throw FileNotFoundException("Injected final rename failure")
        }
        val target = File(root, displayName)
        if (!source.renameTo(target)) throw FileNotFoundException("Could not rename ${source.name}")
        return id(target)
    }

    override fun deleteDocument(documentId: String) {
        val target = file(documentId)
        if (!target.delete()) throw FileNotFoundException(documentId)
    }

    override fun isChildDocument(
        parentDocumentId: String,
        documentId: String,
    ): Boolean = parentDocumentId == ROOT_ID && (documentId == ROOT_ID || documentId.startsWith("$ROOT_ID/"))

    private fun file(documentId: String): File =
        when {
            documentId == ROOT_ID -> root
            documentId.startsWith("$ROOT_ID/") -> File(root, documentId.removePrefix("$ROOT_ID/"))
            else -> throw FileNotFoundException(documentId)
        }

    private fun id(file: File): String = if (file == root) ROOT_ID else "$ROOT_ID/${file.name}"

    private fun MatrixCursor.addDocumentRow(
        columns: Array<out String>,
        documentId: String,
        file: File,
    ) {
        addRow(
            columns,
            mapOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID to documentId,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME to file.name,
                DocumentsContract.Document.COLUMN_MIME_TYPE to
                    if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else SafConfigurationDirectory.MIME,
                DocumentsContract.Document.COLUMN_FLAGS to
                    if (file.isDirectory) {
                        DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                    } else {
                        DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                            DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                            DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                    },
                DocumentsContract.Document.COLUMN_SIZE to file.length(),
                DocumentsContract.Document.COLUMN_LAST_MODIFIED to file.lastModified(),
            ),
        )
    }

    private fun MatrixCursor.addRow(
        columns: Array<out String>,
        values: Map<String, Any>,
    ) {
        val row = newRow()
        columns.forEach { column -> row.add(values[column]) }
    }

    companion object {
        const val ROOT_ID = "root"

        private val ROOT_COLUMNS =
            arrayOf(
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_FLAGS,
            )
        private val DOCUMENT_COLUMNS =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
    }
}
