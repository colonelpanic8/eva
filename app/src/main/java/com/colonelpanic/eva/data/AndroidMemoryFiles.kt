package com.colonelpanic.eva.data

import android.util.AtomicFile
import java.io.File

/** Memory files in app storage, replaced through [AtomicFile] so an interrupted write keeps the last copy. */
class AndroidMemoryFiles(
    private val directory: File,
) : MemoryFiles {
    override fun read(name: String): String? {
        val file = AtomicFile(File(directory, name))
        return try {
            file.openRead().bufferedReader().use { it.readText() }
        } catch (failure: java.io.FileNotFoundException) {
            if (file.baseFile.exists()) throw failure
            null
        }
    }

    override fun write(
        name: String,
        text: String,
    ) {
        val file = AtomicFile(File(directory, name))
        val stream = file.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }
}
