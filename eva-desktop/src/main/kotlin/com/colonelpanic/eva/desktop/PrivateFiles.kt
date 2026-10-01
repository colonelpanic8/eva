package com.colonelpanic.eva.desktop

import com.colonelpanic.eva.data.MemoryFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Replaces [file] with [text] through a temporary sibling and an atomic rename, readable only by
 * the user where the filesystem supports POSIX permissions.
 */
internal fun writePrivately(
    file: File,
    text: String,
) {
    val directory = requireNotNull(file.parentFile).also { it.mkdirs() }
    val temporary = File.createTempFile(".${file.name}.", ".tmp", directory)
    try {
        runCatching { Files.setPosixFilePermissions(temporary.toPath(), PosixFilePermissions.fromString("rw-------")) }
        temporary.writeText(text)
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
}

/** Memory notes as files in one directory. */
class DirectoryMemoryFiles(
    private val directory: File,
) : MemoryFiles {
    override fun read(name: String): String? = File(directory, name).takeIf { it.isFile }?.readText()

    override fun write(
        name: String,
        text: String,
    ) = writePrivately(File(directory, name), text)
}
