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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PluginRepositoryTest {
    private val source = "https://plugins.example.test/index.json"
    private val json =
        requireNotNull(javaClass.getResourceAsStream("/packages/caffeine.json"))
            .bufferedReader()
            .use { it.readText() }

    private fun index(
        json: String,
        url: String = "caffeine.json",
    ): String {
        val definition = PackageCodec.decode(json)
        val hash = MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) }
        return JsonObject(
            mapOf(
                "formatVersion" to JsonPrimitive(1),
                "packages" to
                    JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive(definition.id),
                                    "version" to JsonPrimitive(definition.version),
                                    "title" to JsonPrimitive(definition.title),
                                    "url" to JsonPrimitive(url),
                                    "sha256" to JsonPrimitive(hash),
                                    "androidPackages" to JsonArray(definition.androidPackages.map(::JsonPrimitive)),
                                ),
                            ),
                        ),
                    ),
            ),
        ).toString()
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
    fun `index destinations and file digest are validated before preview`() {
        var document = index(json)
        var file = json
        val repo = PluginRepository { url, _ -> (if (url == source) document else file).toByteArray() }
        val listing = repo.list(source).single()
        assertEquals("Caffeine", repo.preview(source, listing).definition.title)
        file += " "
        assertThrows(IllegalArgumentException::class.java) { repo.preview(source, listing) }
        document = index(json, "https://other.example.test/plugin.json")
        assertThrows(IllegalArgumentException::class.java) { repo.list(source) }
        assertThrows(IllegalArgumentException::class.java) { repo.list("http://plugins.example.test/index.json") }
    }

    @Test
    fun `installation persists exact approved bytes retains identity on update and refuses rollback`() {
        var disk: String? = null
        var fail = false
        val store = PluginInstallations({ disk }, { if (fail) error("disk failed") else disk = it })

        fun preview(value: String) = PluginPreview(source, "https://plugins.example.test/caffeine.json", value, PackageCodec.decode(value))
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
    fun `repository addition reaches the registry without an app rebuild and updates revoke grants`() =
        runTest {
            var disk: String? = null
            val store = PluginInstallations({ disk }, { disk = it })
            var remoteIndex = """{"formatVersion":1,"packages":[]}"""
            var remoteFile = json
            var fetches = 0
            val repo =
                PluginRepository { url, _ ->
                    fetches++
                    (if (url == source) remoteIndex else remoteFile).toByteArray()
                }
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
            val browser =
                PluginBrowser(
                    repo,
                    backgroundScope,
                    source,
                    store::all,
                    { setOf("moe.zhs.caffeine") },
                    {},
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
            browser.refresh(source)
            runCurrent()
            assertTrue(
                browser.state.value.listings
                    .isEmpty(),
            )
            remoteIndex = index(remoteFile)
            browser.refresh(source)
            runCurrent()
            assertEquals(1, browser.state.value.listings.size)
            browser.preview("android.caffeine")
            runCurrent()
            val fetched = fetches
            browser.installPreview()
            runCurrent()
            assertEquals(fetched, fetches)
            assertTrue(registry.catalog.isEmpty())
            val entry =
                runtime.settings.value.entries
                    .single()
            runtime.enable(entry.key, true)
            runCurrent()
            assertTrue(registry.catalog.isEmpty())
            runtime.mutation(entry.key, "enable", true)
            runCurrent()
            val definition = registry.catalog.single()
            val dispatcher = CapabilityDispatcher(registry, MemoryInvocationRepository())
            val proposal = ToolProposal("first", definition.id, emptyMap(), "Keep awake", registry.snapshot.revision)
            assertEquals(InvocationStatus.HANDED_OFF, dispatcher.execute(proposal).status)
            assertEquals(1, launches)
            remoteFile = json.replace("0.1.0", "0.2.0")
            remoteIndex = index(remoteFile)
            browser.refresh(source)
            runCurrent()
            browser.preview("android.caffeine")
            runCurrent()
            browser.installPreview()
            runCurrent()
            assertTrue(registry.catalog.isEmpty())
            assertFalse(
                runtime.settings.value.entries
                    .single()
                    .enabled,
            )
            assertEquals(
                entry.installed.identity,
                runtime.settings.value.entries
                    .single()
                    .installed.identity,
            )
        }
}
