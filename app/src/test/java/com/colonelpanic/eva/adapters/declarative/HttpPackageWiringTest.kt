package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.BoundedExecution
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InteractionMode
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.capability.WaitBudget
import com.colonelpanic.eva.capability.extensions.ExtensionGrantPersistence
import com.colonelpanic.eva.capability.extensions.ExtensionGrants
import com.colonelpanic.eva.capability.extensions.ExtensionRuntime
import com.colonelpanic.eva.capability.extensions.PackageIdentity
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HttpPackageWiringTest {
    private val source =
        PackageCodec.decode(
            generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
                .map { File(it, "docs/examples/org-agenda.json") }
                .first { it.isFile }
                .readText(),
        )

    @Test
    fun `bearer failures and echoed secrets are absent from dispatcher journal`() =
        runTest {
            val token = "fixture-sensitive-token"
            val bearer =
                PackageCodec.decode(
                    source.document.toString().replace(
                        "\"credential\":\"org-agenda\"",
                        "\"credential\":\"org-agenda\",\"credentialScheme\":\"bearer\"",
                    ),
                )
            var saved: HttpCredential? = BearerCredential.create("https://agenda.example.org", token)
            var echo = true
            val http =
                PackageHttpClient(
                    { _, _ -> saved },
                    okhttp3.OkHttpClient
                        .Builder()
                        .addInterceptor { chain ->
                            if (!echo) throw java.io.IOException("Transport exposed $token")
                            okhttp3.Response
                                .Builder()
                                .request(chain.request())
                                .protocol(okhttp3.Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body("{\"echo\":\"$token\"}".toResponseBody())
                                .build()
                        }.build(),
                )
            val host =
                object : DeclarativeHost {
                    override suspend fun unavailableReason(binding: DeclarativeBinding): String? = null

                    override suspend fun launch(request: IntentRequest): ExecutionOutcome = error("unused")

                    override suspend fun query(
                        request: ContentRequest,
                        timeoutMillis: Long,
                    ): ContentRows = error("unused")

                    override suspend fun request(
                        request: HttpRequest,
                        timeoutMillis: Long,
                    ) = http.execute(request, timeoutMillis)
                }
            val loaded = LoadedPackage(PackageIdentity("00000000-0000-0000-0000-000000000099"), bearer, true)
            val adapter =
                PackageAdapter({ listOf(loaded) }, { host }, BoundedExecution(backgroundScope)) { _, cap, proposal ->
                    WaitBudget(proposal.interactionMode, 30_000, cap.execution.maxWaitMillis, null)
                }
            val persistence =
                object : ExtensionGrantPersistence {
                    var value: String? = null

                    override suspend fun read() = value

                    override suspend fun write(json: String) {
                        value = json
                    }
                }
            val registry = CapabilityRegistry(emptyMap())
            val runtime = ExtensionRuntime(registry, adapter, ExtensionGrants(persistence), backgroundScope)
            runCurrent()
            runtime.enable(
                runtime.settings.value.entries
                    .single()
                    .key,
                true,
            )
            runCurrent()
            val journal = MemoryInvocationRepository()
            val dispatcher = CapabilityDispatcher(registry, journal)
            val cap = registry.catalog.single { it.id.endsWith(".agenda") }

            suspend fun dispatch(id: String) =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    dispatcher.execute(ToolProposal(id, cap.id, emptyMap(), "read", registry.snapshot.revision))
                }
            val echoed = dispatch("echo")
            assertEquals(InvocationStatus.UNKNOWN, echoed.status)
            echo = false
            val failed = dispatch("transport")
            assertEquals(InvocationStatus.UNKNOWN, failed.status)
            saved = null
            val missing = dispatch("missing")
            assertEquals(InvocationStatus.NOT_EXECUTED, missing.status)
            assertFalse(listOf(echoed, failed, missing).toString().contains(token))
            assertEquals(3, journal.history().size)
            assertFalse(journal.history().toString().contains(token))
        }

    @Test
    fun `approved origin changes digest and credentials reject ambiguous destinations`() {
        val credential = BasicCredential.create("https://agenda.example.test/", "user", "password")
        assertEquals("https://agenda.example.test", credential.origin)
        assertEquals(credential, BasicCredential.decode(credential.encode()))
        val configured = configurePackage(source, credential.origin)
        assertNotEquals(source.digest, configured.digest)
        assertTrue(configured.httpBindings().all { it.origin == credential.origin })
        listOf("http://host", "https://u:p@host", "https://host/path", "https://host/?x=1", "https://host/#x").forEach {
            assertThrows(IllegalArgumentException::class.java) { BasicCredential.create(it, "user", "password") }
        }
    }

    @Test
    fun `HTTP fixture requires grants dispatches through host and journals budget and provenance`() =
        runTest {
            val identity = PackageIdentity("00000000-0000-0000-0000-000000000001")
            var loaded = LoadedPackage(identity, configurePackage(source, "https://agenda.example.test"), true)
            var calls = 0
            val host =
                object : DeclarativeHost {
                    override suspend fun unavailableReason(binding: DeclarativeBinding): String? = null

                    override suspend fun launch(request: IntentRequest) = ExecutionOutcome(InvocationStatus.HANDED_OFF, "Opened")

                    override suspend fun query(
                        request: ContentRequest,
                        timeoutMillis: Long,
                    ): ContentRows = error("unused")

                    override suspend fun request(
                        request: HttpRequest,
                        timeoutMillis: Long,
                    ): HttpResponse {
                        calls++
                        assertTrue(request.url.startsWith("https://agenda.example.test/"))
                        assertEquals(45_000, timeoutMillis)
                        return if (request.method == "GET") {
                            HttpResponse(200, """{"entries":[{"title":"Test","id":"stable"}]}""")
                        } else {
                            HttpResponse(200, """{"status":"created"}""")
                        }
                    }
                }
            val adapter =
                PackageAdapter({ listOf(loaded) }, { host }, BoundedExecution(backgroundScope)) { _, cap, proposal ->
                    WaitBudget(proposal.interactionMode, 30_000, cap.execution.maxWaitMillis, 45_000)
                }
            val persistence =
                object : ExtensionGrantPersistence {
                    var value: String? = null

                    override suspend fun read() = value

                    override suspend fun write(json: String) {
                        value = json
                    }
                }
            val registry = CapabilityRegistry(emptyMap())
            val runtime = ExtensionRuntime(registry, adapter, ExtensionGrants(persistence), backgroundScope)
            runCurrent()
            assertTrue(registry.catalog.isEmpty())
            var key =
                runtime.settings.value.entries
                    .single()
                    .key
            runtime.enable(key, true)
            runCurrent()
            assertEquals(setOf("agenda", "search_todos", "custom_view"), registry.catalog.map { it.id.substringAfterLast('.') }.toSet())
            val journal = MemoryInvocationRepository()
            val dispatcher = CapabilityDispatcher(registry, journal)
            val read = registry.catalog.single { it.id.endsWith(".agenda") }
            val result = dispatcher.execute(ToolProposal("read", read.id, emptyMap(), "agenda", registry.snapshot.revision))
            assertEquals(InvocationStatus.COMPLETED, result.status)
            assertTrue(result.message.contains("extension=45000"))
            assertEquals("Org agenda", result.provenance!!.source.title)
            val restored =
                com.colonelpanic.eva.capability.ReceiptProvenance
                    .fromJson(result.provenance.toJson())
            assertEquals(45_000, restored.waitBudget!!.effectiveMillis)
            assertEquals(30_000L, restored.waitBudget.capabilityDefaultMillis)
            assertEquals(30_000, restored.waitBudget.modeDefaultMillis)
            runtime.mutation(key, "capture", true)
            runCurrent()
            val capture = registry.catalog.single { it.id.endsWith(".capture") }
            val write = ToolProposal("write", capture.id, mapOf("title" to "Test"), "capture", registry.snapshot.revision)
            assertEquals(InvocationStatus.COMPLETED, dispatcher.execute(write).status)
            assertEquals(2, calls)
            loaded = loaded.copy(definition = configurePackage(source, "https://changed.example.test"))
            adapter.refresh()
            // The synchronous gate closes before the runtime processes the new snapshot.
            assertEquals(InvocationStatus.NOT_EXECUTED, dispatcher.execute(write.copy(callId = "stale")).status)
            runCurrent()
            assertFalse(
                runtime.settings.value.entries
                    .single()
                    .enabled,
            )
            assertEquals(2, calls)
            assertEquals(3, journal.history().size)
        }
}
