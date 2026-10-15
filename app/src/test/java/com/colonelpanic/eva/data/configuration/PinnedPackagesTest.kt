package com.colonelpanic.eva.data.configuration

import android.app.Application
import android.content.Context
import com.colonelpanic.eva.adapters.declarative.DefaultPackages
import com.colonelpanic.eva.adapters.declarative.PackageCodec
import com.colonelpanic.eva.adapters.declarative.PluginRepository
import com.colonelpanic.eva.data.PackageSettings
import com.colonelpanic.eva.data.PortablePackageSettings
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class PinnedPackagesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `fresh restore uses the exact shipped revision without a catalog checkout`() {
        val cache = File(context.cacheDir, "unused-catalog-cache")
        val assets =
            generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
                .map { File(it, "app/src/main/assets") }
                .first { it.isDirectory }
        val settings =
            PackageSettings(
                context,
                readAsset = { File(assets, it).readText() },
                repository = PluginRepository(cache, { _, _ -> error("No network expected") }),
            )
        val target =
            PortablePackageSettings(
                DefaultPackages.all.map { it.source }.distinct(),
                DefaultPackages.all.map {
                    PortablePackage(it.identity.id, it.source, it.path, revision = DefaultPackages.revisions.getValue(it.path), id = it.id)
                },
                emptyMap(),
                emptyList(),
            )
        settings.restore(target)
        assertEquals(target, settings.portable())
        assertEquals(DefaultPackages.all.size, settings.imported().size)
        assertFalse(cache.exists())
    }

    @Test
    fun `restore fetches referenced definitions exports only pins and preserves installed state on a bad reference`() {
        val root = Files.createTempDirectory("eva-pinned-packages").toFile()
        try {
            val remote = File(root, "catalog")
            val json =
                generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
                    .map { File(it, "app/src/main/assets/packages/clock.json") }
                    .first { it.isFile }
                    .readText()
            val revision =
                Git.init().setDirectory(remote).call().use { git ->
                    File(remote, "packages").mkdirs()
                    File(remote, "packages/clock.json").writeText(json)
                    git.add().addFilepattern(".").call()
                    git
                        .commit()
                        .setMessage("Clock")
                        .setAuthor("Test", "test@example.test")
                        .call()
                        .name
                }
            val source = remote.toURI().toString().trimEnd('/')
            val repository = PluginRepository(File(root, "cache"), { _, _ -> error("No HTTP fetch") }, true)
            val settings = PackageSettings(context, repository = repository)
            val instance = "00000000-0000-0000-0000-000000000091"
            val target =
                PortablePackageSettings(
                    listOf(source),
                    listOf(PortablePackage(instance, source, "packages/clock.json", id = "android.clock")),
                    mapOf(instance to 12_000L),
                    emptyList(),
                    revisions = mapOf(source to revision),
                )
            settings.restore(target)
            assertEquals(
                PackageCodec.decode(json).digest,
                settings
                    .imported()
                    .single()
                    .definition.digest,
            )
            assertEquals(target, settings.portable())
            assertNull(
                settings
                    .portable()
                    .installed
                    .single()
                    .document,
            )
            assertEquals(revision, settings.imported().single().revision)
            val incorrect = target.copy(installed = target.installed.map { it.copy(id = "android.wrong") })
            assertThrows(IllegalArgumentException::class.java) { settings.restore(incorrect) }
            assertEquals(target, settings.portable())
            val missing = target.copy(installed = target.installed.map { it.copy(url = "packages/missing.json") })
            assertThrows(IllegalArgumentException::class.java) { settings.restore(missing) }
            assertEquals(target, settings.portable())
            remote.deleteRecursively()
            File(root, "cache").deleteRecursively()
            val restarted = PackageSettings(context, repository = repository)
            restarted.restore(target)
            assertEquals(target, restarted.portable())
            assertEquals(json, restarted.imported().single().json)
        } finally {
            root.deleteRecursively()
        }
    }
}
