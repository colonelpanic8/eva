package com.colonelpanic.eva.data.configuration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileConfigurationDirectoryTest {
    @Test
    fun `backup-only interrupted replacement is recovered`() {
        val root = Files.createTempDirectory("eva-file-configuration").toFile()
        val text = "format: eva\nversion: 2\n"
        File(root, ".eva.yaml.backup").writeText(text)
        val directory = FileConfigurationDirectory(root)

        assertEquals(text, directory.read(EvaConfigurationCodec.FILE_NAME))
        assertTrue(File(root, EvaConfigurationCodec.FILE_NAME).isFile)
        assertFalse(File(root, ".eva.yaml.backup").exists())
    }

    @Test
    fun `interrupted fallback copy restores backup over partial root`() {
        val root = Files.createTempDirectory("eva-file-configuration").toFile()
        val previous = "format: eva\nversion: 2\n"
        File(root, EvaConfigurationCodec.FILE_NAME).writeText("partial")
        File(root, ".eva.yaml.new").writeText("new value")
        File(root, ".eva.yaml.backup").writeText(previous)
        val directory = FileConfigurationDirectory(root)

        assertEquals(previous, directory.read(EvaConfigurationCodec.FILE_NAME))
        assertFalse(File(root, ".eva.yaml.new").exists())
        assertFalse(File(root, ".eva.yaml.backup").exists())
    }

    @Test
    fun `stale backup is cleared before a new atomic update`() {
        val root = Files.createTempDirectory("eva-file-configuration").toFile()
        val current = "format: eva\nversion: 2\nmodels:\n  text: current\n"
        val replacement = "format: eva\nversion: 2\nmodels:\n  text: replacement\n"
        File(root, EvaConfigurationCodec.FILE_NAME).writeText(current)
        File(root, ".eva.yaml.backup").writeText("format: eva\nversion: 2\nmodels:\n  text: stale\n")
        val directory = FileConfigurationDirectory(root)

        directory.replaceRoot(replacement, EvaConfigurationCodec.fingerprint(current))

        assertEquals(replacement, directory.read(EvaConfigurationCodec.FILE_NAME))
        assertFalse(File(root, ".eva.yaml.backup").exists())
    }

    @Test
    fun `configuration symlink is rejected`() {
        val root = Files.createTempDirectory("eva-file-configuration").toFile()
        val outside = File(root.parentFile, "outside-${root.name}.yaml").apply { writeText("secret") }
        Files.createSymbolicLink(File(root, EvaConfigurationCodec.FILE_NAME).toPath(), outside.toPath())

        val failure = runCatching { FileConfigurationDirectory(root).read(EvaConfigurationCodec.FILE_NAME) }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("symbolic link"))
    }
}
