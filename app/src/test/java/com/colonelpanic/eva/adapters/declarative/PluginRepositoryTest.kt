package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.BoundedExecution
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.capability.WaitBudget
import com.colonelpanic.eva.capability.extensions.ExtensionGrantPersistence
import com.colonelpanic.eva.capability.extensions.ExtensionGrants
import com.colonelpanic.eva.capability.extensions.ExtensionRuntime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PluginRepositoryTest {
    private val messages =
        requireNotNull(javaClass.getResourceAsStream("/packages/messages.json"))
            .bufferedReader()
            .use { it.readText() }
    private val json =
        requireNotNull(javaClass.getResourceAsStream("/packages/caffeine.json"))
            .bufferedReader()
            .use { it.readText() }
    private val root = Files.createTempDirectory("eva-catalog").toFile()

    /** A bare remote plus a publishing clone standing in for the catalog repository on its host. */
    private inner class Catalog {
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
        val source: String = remote.toURI().toString()

        fun publish(
            name: String,
            content: String,
        ) {
            File(work, "packages/$name").apply {
                parentFile.mkdirs()
                writeText(content)
            }
            git.add().addFilepattern(".").call()
            git
                .commit()
                .setMessage("publish $name")
                .setAuthor("Catalog", "catalog@example.test")
                .call()
            git
                .push()
                .setRemote("origin")
                .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main"))
                .call()
        }

        fun close() = git.close()
    }

    private val catalog = Catalog()
    private val repository = PluginRepository(File(root, "checkouts"), { _, _ -> error("The catalog is not fetched over HTTP") }, true)

    @After
    fun cleanUp() {
        catalog.close()
        root.deleteRecursively()
    }

    @Test
    fun `catalog listing follows the remote head and reports unreadable files without hiding the rest`() {
        catalog.publish("caffeine.json", json)
        val first = repository.list(catalog.source)
        val listing = first.listings.single()
        assertEquals("android.caffeine", listing.id)
        assertEquals("0.1.0", listing.version)
        assertEquals("packages/caffeine.json", listing.url)
        assertEquals(listOf("moe.zhs.caffeine"), listing.androidPackages)
        assertTrue(first.problems.isEmpty())
        assertEquals(json, repository.preview(catalog.source, listing).json)

        catalog.publish("broken.json", "{")
        catalog.publish("caffeine.json", json.replace("0.1.0", "0.2.0"))
        val second = repository.list(catalog.source)
        assertEquals("0.2.0", second.listings.single().version)
        assertEquals(1, second.problems.size)
        assertTrue(second.problems.single().startsWith("broken.json"))
        assertThrows(IllegalArgumentException::class.java) { repository.preview(catalog.source, listing) }
        val preview = repository.preview(catalog.source, second.listings.single())
        assertEquals(catalog.source.trimEnd('/'), preview.source)
        assertEquals("packages/caffeine.json", preview.url)
    }

    @Test
    fun `catalog sources are HTTPS git remotes and index era identities map onto them`() {
        assertEquals(
            "https://github.com/colonelpanic8/eva-extensions.git",
            PluginRepository.installationSource("https://raw.githubusercontent.com/colonelpanic8/eva-extensions/main/index.json"),
        )
        assertEquals(
            "packages/google-maps.json",
            PluginRepository.legacyUrl("https://raw.githubusercontent.com/colonelpanic8/eva-extensions/main/packages/google-maps.json"),
        )
        assertEquals(
            "https://plugins.example.test/catalog.git",
            PluginRepository.catalogSource(" https://plugins.example.test/catalog.git/ "),
        )
        assertEquals("packages/x.json", PluginRepository.installationUrl("packages/x.json"))
        assertThrows(IllegalArgumentException::class.java) { PluginRepository.catalogSource("http://plugins.example.test/catalog.git") }
        assertThrows(IllegalArgumentException::class.java) { PluginRepository.catalogSource("https://user:pw@plugins.example.test/c.git") }
        assertThrows(IllegalArgumentException::class.java) { PluginRepository.catalogSource(catalog.source) }
        assertThrows(IllegalArgumentException::class.java) { PluginRepository.installationUrl("../packages/x.json") }
        assertThrows(Exception::class.java) { PluginRepository(File(root, "other"), { _, _ -> ByteArray(0) }).list(catalog.source) }
    }

    @Test
    fun `file import is bounded closes the stream and cannot replace another source`() {
        var closed = false
        val input =
            object : java.io.ByteArrayInputStream(json.toByteArray()) {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        val preview = PluginRepository.filePreview(input)
        assertTrue(closed)
        var disk: String? = null
        val store = PluginInstallations({ disk }, { disk = it })
        val first = store.install(preview)
        val second = store.install(PluginRepository.filePreview(json.byteInputStream()))
        assertFalse(first.identity == second.identity)
        assertEquals(2, PluginInstallations({ disk }, {}).all().size)
        assertThrows(IllegalArgumentException::class.java) {
            PluginRepository.filePreview(ByteArray(PackageCodec.MAX_BYTES + 1).inputStream())
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginRepository.filePreview("invalid json".byteInputStream())
        }
    }

    @Test
    fun `installation persists exact approved bytes retains identity on update and refuses rollback`() {
        var disk: String? = null
        var fail = false
        val store = PluginInstallations({ disk }, { if (fail) error("disk failed") else disk = it })
        val source = "https://plugins.example.test/catalog.git"

        fun preview(value: String) = PluginPreview(source, "packages/caffeine.json", value, PackageCodec.decode(value))
        val first = store.install(preview(json))
        assertEquals(json, PluginInstallations({ disk }, {}).all().single().json)
        val next = json.replace("0.1.0", "0.2.0").replace("Caffeine", "Caffeine updated")
        fail = true
        assertThrows(IllegalStateException::class.java) { store.install(preview(next)) }
        assertEquals(
            first.definition.digest,
            store
                .all()
                .single()
                .definition.digest,
        )
        fail = false
        assertEquals(first.identity, store.install(preview(next)).identity)
        assertThrows(IllegalArgumentException::class.java) { store.install(preview(json)) }
        store.remove(first.identity.id)
        assertTrue(PluginInstallations({ disk }, {}).all().isEmpty())
    }

    @Test
    fun `stored index era installations reload as catalog installations`() {
        val disk =
            """[{"instance":"00000000-0000-0000-0000-000000000009",
            "source":"https://raw.githubusercontent.com/colonelpanic8/eva-extensions/main/index.json",
            "url":"https://raw.githubusercontent.com/colonelpanic8/eva-extensions/main/packages/caffeine.json",
            "json":${JsonPrimitive(json)}}]"""
        val plugin = PluginInstallations({ disk }, {}).all().single()
        assertEquals("https://github.com/colonelpanic8/eva-extensions.git", plugin.source)
        assertEquals("packages/caffeine.json", plugin.url)
    }

    @Test
    fun `a followed catalog installs and enables what it publishes and keeps approvals across updates`() =
        runTest {
            var disk: String? = null
            val store = PluginInstallations({ disk }, { disk = it })
            var launches = 0
            val host =
                object : DeclarativeHost {
                    override suspend fun unavailableReason(binding: DeclarativeBinding): String? = null

                    override suspend fun launch(request: IntentRequest): ExecutionOutcome {
                        launches++
                        assertEquals("moe.zhs.caffeine.ToggleActivity", request.targetClass)
                        assertEquals(JsonPrimitive(1), request.extras["Status"])
                        return ExecutionOutcome(InvocationStatus.HANDED_OFF, "Handed off")
                    }

                    override suspend fun query(
                        request: ContentRequest,
                        timeoutMillis: Long,
                    ): ContentRows = error("unused")

                    override suspend fun request(
                        request: HttpRequest,
                        timeoutMillis: Long,
                    ): HttpResponse = error("unused")
                }
            val adapter =
                PackageAdapter(
                    { store.all().map { LoadedPackage(it.identity, it.definition, true) } },
                    { host },
                    BoundedExecution(backgroundScope),
                ) { _, cap, proposal ->
                    WaitBudget(proposal.interactionMode, 30_000, cap.execution.maxWaitMillis, null)
                }
            val registry = CapabilityRegistry(emptyMap())
            val grants =
                ExtensionGrants(
                    object : ExtensionGrantPersistence {
                        var saved: String? = null

                        override suspend fun read() = saved

                        override suspend fun write(json: String) {
                            saved = json
                        }
                    },
                )
            val runtime = ExtensionRuntime(registry, adapter, grants, backgroundScope)
            var autoEnable = true
            val browser =
                PluginBrowser(
                    repository,
                    backgroundScope,
                    { listOf(catalog.source) },
                    store::all,
                    { setOf("moe.zhs.caffeine") },
                    {},
                    PluginSyncPolicy(
                        store::all,
                        { autoEnable },
                        { preview ->
                            var installed: InstalledPlugin? = null
                            registry.changeAuthorization {
                                installed = store.install(preview)
                                adapter.refresh()
                            }
                            checkNotNull(installed)
                        },
                        { identity -> runtime.adopt(identity) },
                        { identity, digest, actions, enabled -> runtime.carryForward(identity, digest, actions, enabled) },
                    ),
                    {
                        registry.changeAuthorization {
                            store.install(it)
                            adapter.refresh()
                        }
                    },
                    {
                        registry.changeAuthorization {
                            store.remove(it)
                            adapter.refresh()
                        }
                    },
                )
            catalog.publish("README.json.txt", "not a package")
            browser.refresh(catalog.source)
            runCurrent()
            assertTrue(store.all().isEmpty())

            catalog.publish("caffeine.json", json)
            browser.refresh(catalog.source)
            runCurrent()
            assertEquals(catalog.source.trimEnd('/'), store.all().single().source)
            val entry =
                runtime.settings.value.entries
                    .single()
            assertTrue("A followed catalog enables what it installs", entry.enabled)
            assertEquals("Both of Caffeine's actions are approved", 2, registry.catalog.size)
            val definition = registry.catalog.first { it.id.endsWith(".enable") }
            val dispatcher = CapabilityDispatcher(registry, MemoryInvocationRepository())
            val proposal = ToolProposal("first", definition.id, emptyMap(), "Keep awake", registry.snapshot.revision)
            assertEquals(InvocationStatus.HANDED_OFF, dispatcher.execute(proposal).status)
            assertEquals(1, launches)

            runtime.mutation(entry.key, "disable", false)
            runCurrent()

            fun revision(
                version: String,
                addedActions: List<String>,
            ): String {
                val root = Json.parseToJsonElement(json).jsonObject
                val declared = root.getValue("capabilities").jsonArray
                val template = declared.last().jsonObject
                val additions =
                    addedActions.map { name ->
                        JsonObject(
                            template + ("tool" to JsonObject(template.getValue("tool").jsonObject + ("name" to JsonPrimitive(name)))),
                        )
                    }
                return JsonObject(
                    root + ("version" to JsonPrimitive(version)) + ("capabilities" to JsonArray(declared + additions)),
                ).toString()
            }

            catalog.publish("caffeine.json", revision("0.2.0", listOf("pause")))
            browser.refresh(catalog.source)
            runCurrent()
            assertEquals(
                "0.2.0",
                store
                    .all()
                    .single()
                    .definition.version,
            )
            val updated =
                runtime.settings.value.entries
                    .single()
            assertTrue("An update keeps the approvals it already had", updated.enabled)
            assertEquals("A new action is approved but a declined one stays off", setOf("enable", "pause"), updated.mutations)
            assertEquals(entry.installed.identity, updated.installed.identity)
            assertEquals(
                InvocationStatus.HANDED_OFF,
                dispatcher
                    .execute(
                        ToolProposal(
                            "second",
                            registry.catalog.first { it.id.endsWith(".enable") }.id,
                            emptyMap(),
                            "Keep awake",
                            registry.snapshot.revision,
                        ),
                    ).status,
            )

            autoEnable = false
            catalog.publish("caffeine.json", revision("0.2.1", listOf("pause", "resume")))
            browser.refresh(catalog.source)
            runCurrent()
            assertEquals(
                "Auto-enable off leaves later new actions off",
                setOf("enable", "pause"),
                runtime.settings.value.entries
                    .single()
                    .mutations,
            )

            // The key carries the live digest, so an update means re-reading it before toggling.
            runtime.enable(
                runtime.settings.value.entries
                    .single()
                    .key,
                false,
            )
            runCurrent()
            catalog.publish("caffeine.json", json.replace("0.1.0", "0.3.0"))
            browser.refresh(catalog.source)
            runCurrent()
            assertFalse(
                "A refresh does not undo turning an extension off",
                runtime.settings.value.entries
                    .single()
                    .enabled,
            )

            autoEnable = false
            catalog.publish("messages.json", messages)
            browser.refresh(catalog.source)
            runCurrent()
            assertEquals(2, store.all().size)
            assertTrue(
                "Auto-enable off installs without approving",
                runtime.settings.value.entries
                    .none { it.enabled },
            )
        }
}
