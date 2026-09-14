package com.colonelpanic.eva.data

import android.content.Context
import android.util.AtomicFile
import com.colonelpanic.eva.capability.extensions.ExtensionGrantPersistence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

class ExtensionGrantFile(
    context: Context,
) : ExtensionGrantPersistence {
    private val file = AtomicFile(File(context.noBackupFilesDir, "extension-grants.json"))

    override suspend fun read(): String? =
        withContext(Dispatchers.IO) {
            try {
                file.openRead().use { stream ->
                    require(stream.channel.size() <= 1_048_576)
                    val bytes = stream.readBytes()
                    require(bytes.size <= 1_048_576)
                    bytes.toString(Charsets.UTF_8)
                }
            } catch (_: FileNotFoundException) {
                null
            }
        }

    override suspend fun write(json: String) =
        withContext(Dispatchers.IO) {
            val stream = file.startWrite()
            try {
                stream.write(json.toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }
}
