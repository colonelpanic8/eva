package com.colonelpanic.eva.desktop

import com.colonelpanic.eva.data.configuration.EvaConfigurationCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class DesktopConfigurationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `model edits preserve included files and phone settings and reload`() {
        val shared =
            folder.newFile("shared.yaml").apply {
                writeText("format: eva\nversion: 3\nmodels:\n  text: gpt-test\n  reasoningEffort: high\n")
            }
        val file =
            folder.newFile("eva.yaml").apply {
                writeText("format: eva\nversion: 3\ninclude:\n  - shared.yaml\nvoice:\n  lookupRetries: 4\n")
            }
        val configuration = DesktopConfiguration(file)
        assertEquals("gpt-test", configuration.state.value.configuration.models.text)
        val included = shared.readText()
        configuration.saveModels("gpt-other", "low")
        assertEquals(included, shared.readText())
        val root = EvaConfigurationCodec.decode(file.readText())
        assertEquals(listOf("shared.yaml"), root.include)
        assertEquals(4, root.voice?.lookupRetries)
        assertEquals(
            "gpt-other",
            DesktopConfiguration(file)
                .state.value.configuration.models.text,
        )
    }

    @Test fun `invalid edits and stale editor saves keep the existing file`() {
        val file = folder.newFile("eva.yaml").apply { writeText("format: eva\nversion: 3\n") }
        val configuration = DesktopConfiguration(file)
        val original = file.readText()
        assertTrue(runCatching { configuration.saveModels("gpt-test", "unsupported") }.isFailure)
        assertEquals(original, file.readText())
        file.appendText("voice:\n  lookupRetries: 5\n")
        val external = file.readText()
        assertTrue(runCatching { configuration.saveText(original, original) }.isFailure)
        assertEquals(external, file.readText())
    }

    @Test fun `a symlink continues to edit the repository file`() {
        val repo = folder.newFolder("repo")
        val target = repo.resolve("eva.yaml").apply { writeText("format: eva\nversion: 3\n") }
        val link = folder.root.resolve("eva.yaml")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        DesktopConfiguration(link).saveModels("gpt-test", "medium")
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("gpt-test", EvaConfigurationCodec.decode(target.readText()).models?.text)
    }
}
