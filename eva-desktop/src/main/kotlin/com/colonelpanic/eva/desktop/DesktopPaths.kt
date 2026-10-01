package com.colonelpanic.eva.desktop

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/** Where EVA keeps its files on this computer, following the XDG base directories. */
class DesktopPaths(
    val config: File,
    val data: File,
) {
    val journal get() = File(data, "eva-actions.db")
    val memory get() = File(data, "memory")
    val chatGptTokens get() = File(config, "chatgpt.json")
    private val lockFile get() = File(data, "eva.lock")

    /** Creates both directories readable only by the user, and tightens anything already in them. */
    fun secure() {
        for (directory in listOf(config, data, memory)) {
            directory.mkdirs()
            restrict(directory, "rwx------")
        }
        listOf(config, data, memory).flatMap { it.listFiles()?.filter(File::isFile).orEmpty() }.forEach { restrict(it, "rw-------") }
    }

    /**
     * Exclusive ownership of this EVA's storage. Startup recovery marks unfinished work as
     * interrupted, so only one process may hold it; null when another process does.
     */
    fun lock(): FileLock? {
        secure()
        val channel = FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val lock =
            try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
        if (lock == null) channel.close()
        return lock
    }

    private fun restrict(
        file: File,
        permissions: String,
    ) {
        runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(permissions)) }
    }

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): DesktopPaths {
            val home = File(env["HOME"] ?: System.getProperty("user.home"))
            val config = env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, ".config")
            val data = env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, ".local/share")
            return DesktopPaths(File(config, "eva"), File(data, "eva"))
        }
    }
}
