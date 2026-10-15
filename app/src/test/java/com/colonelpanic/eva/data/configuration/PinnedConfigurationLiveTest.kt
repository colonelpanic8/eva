package com.colonelpanic.eva.data.configuration

import com.colonelpanic.eva.EvaApplication
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = EvaApplication::class)
class PinnedConfigurationLiveTest {
    private val systemReader = SystemReader.getInstance()

    @Before
    fun isolateGitConfiguration() {
        val folder = File(RuntimeEnvironment.getApplication().cacheDir, "isolated-git-config")
        SystemReader.setInstance(
            object : SystemReader.Delegate(systemReader) {
                override fun openUserConfig(
                    parent: org.eclipse.jgit.lib.Config?,
                    fs: FS,
                ) = FileBasedConfig(parent, File(folder, "user"), fs)

                override fun openSystemConfig(
                    parent: org.eclipse.jgit.lib.Config?,
                    fs: FS,
                ) = FileBasedConfig(parent, File(folder, "system"), fs)

                override fun openJGitConfig(
                    parent: org.eclipse.jgit.lib.Config?,
                    fs: FS,
                ) = FileBasedConfig(parent, File(folder, "jgit"), fs)
            },
        )
    }

    @After
    fun restoreGitConfiguration() {
        SystemReader.setInstance(systemReader)
    }

    @Test
    fun `pinned configuration restores from real catalogs and remains compact after a settings edit`() =
        runBlocking {
            val path = System.getenv("EVA_PINNED_CONFIG")
            assumeTrue("Set EVA_PINNED_CONFIG to opt into network restore verification", path != null)
            val app = RuntimeEnvironment.getApplication() as EvaApplication
            val folder = File(app.cacheDir, "pinned-config-verification").apply { mkdirs() }
            File(folder, "eva.yaml").writeText(File(requireNotNull(path)).readText())
            val directory = FileConfigurationDirectory(folder)
            val target = EvaConfigurationCodec.resolve(directory).configuration
            assertTrue(target.packages.installed.all { it.document == null })
            val manager = EvaConfigurationManager(app)
            assertTrue(manager.attachForTest(directory) is LinkedConfigurationResult.Loaded)
            val imported = app.packageSettings.imported()
            assertEquals(
                target.packages.installed
                    .map { it.instance }
                    .toSet(),
                imported.map { it.identity.id }.toSet(),
            )
            assertEquals(
                target.packages.installed
                    .map { it.id }
                    .toSet(),
                imported.map { it.definition.id }.toSet(),
            )
            assertEquals(target.packages, manager.snapshotForTest().packages)
            assertEquals(target.extensions, manager.snapshotForTest().extensions)
            app.appearance.saveDynamicColor(!target.appearance.dynamicColor)
            assertTrue(manager.localChangeForTest() is LinkedConfigurationResult.Saved)
            val saved = EvaConfigurationCodec.resolve(directory).configuration
            assertEquals(target.packages, saved.packages)
            assertEquals(target.extensions, saved.extensions)
            assertFalse(requireNotNull(directory.read("eva.yaml")).contains("document:"))
        }
}
