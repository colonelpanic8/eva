package com.colonelpanic.eva.adapters.declarative

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** What the extensions screen offers as an update, and when it looks for one. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PluginUpdateOfferTest {
    private val json =
        requireNotNull(javaClass.getResourceAsStream("/packages/caffeine.json"))
            .bufferedReader()
            .use { it.readText() }
    private val root = Files.createTempDirectory("eva-updates").toFile()
    private val remote =
        File(root, "remote.git").also {
            Git
                .init()
                .setDirectory(it)
                .setBare(true)
                .setInitialBranch("main")
                .call()
                .close()
        }
    private val work = File(root, "work")
    private val git =
        Git.init().setDirectory(work).setInitialBranch("main").call().also {
            it
                .remoteAdd()
                .setName("origin")
                .setUri(URIish(remote.toURI().toString()))
                .call()
        }
    private val source: String = remote.toURI().toString()
    private val repository = PluginRepository(File(root, "checkouts"), { _, _ -> error("The catalog is not fetched over HTTP") }, true)

    private fun publish(content: String) {
        File(work, "packages/caffeine.json").apply {
            parentFile.mkdirs()
            writeText(content)
        }
        git.add().addFilepattern(".").call()
        git
            .commit()
            .setMessage("publish")
            .setAuthor("Catalog", "catalog@example.test")
            .call()
        git
            .push()
            .setRemote("origin")
            .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main"))
            .call()
    }

    @After
    fun cleanUp() {
        git.close()
        root.deleteRecursively()
    }

    @Test
    fun `a newer catalog version becomes an update offer that installing clears`() =
        runTest {
            publish(json)
            var now = 0L
            var disk: String? = null
            val store = PluginInstallations({ disk }, { disk = it })
            val browser =
                PluginBrowser(
                    repository,
                    this,
                    source,
                    store::all,
                    { emptySet() },
                    {},
                    { store.install(it) },
                    { store.remove(it) },
                ) { now }

            browser.checkForUpdates()
            runCurrent()
            assertTrue(browser.state.value.checked)
            assertTrue(
                "Nothing is installed yet",
                browser.state.value.updates
                    .isEmpty(),
            )

            browser.preview("android.caffeine")
            runCurrent()
            browser.installPreview()
            runCurrent()
            assertEquals(
                "0.1.0",
                store
                    .all()
                    .single()
                    .definition.version,
            )
            assertTrue(
                "The installed version matches the catalog",
                browser.state.value.updates
                    .isEmpty(),
            )

            publish(json.replace("\"version\": \"0.1.0\"", "\"version\": \"0.2.0\""))
            now += CHECK_INTERVAL_MILLIS - 1
            browser.checkForUpdates()
            runCurrent()
            assertTrue(
                "A recent listing is reused instead of refetched",
                browser.state.value.updates
                    .isEmpty(),
            )

            now += 1
            browser.checkForUpdates()
            runCurrent()
            val update =
                browser.state.value.updates
                    .single()
            assertEquals("android.caffeine", update.id)
            assertEquals("0.1.0", update.installedVersion)
            assertEquals("0.2.0", update.availableVersion)

            browser.preview(update.id)
            runCurrent()
            browser.installPreview()
            runCurrent()
            assertEquals(
                "0.2.0",
                store
                    .all()
                    .single()
                    .definition.version,
            )
            assertTrue(
                "The offer goes away once it is taken",
                browser.state.value.updates
                    .isEmpty(),
            )
            assertTrue(
                browser.state.value.notice
                    .orEmpty()
                    .contains("enabled again"),
            )
        }

    @Test
    fun `versions order by component so an older listing is never offered`() {
        assertTrue(PackageVersion.newer("0.10.0", "0.9.9"))
        assertFalse(PackageVersion.newer("0.9.9", "0.10.0"))
        assertFalse(PackageVersion.newer("1.2.3", "1.2.3"))
    }
}
