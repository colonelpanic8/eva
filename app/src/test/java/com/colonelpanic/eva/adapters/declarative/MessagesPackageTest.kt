package com.colonelpanic.eva.adapters.declarative

import android.app.Application
import android.content.Intent
import com.colonelpanic.eva.adapters.android.AndroidDeclarativeHost
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
import com.colonelpanic.eva.capability.extensions.PackageIdentity
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessagesPackageTest {
    private val json =
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "app/src/main/assets/messages.json") }
            .first { it.isFile }
            .readText()
    private val source = PackageCodec.decode(json)
    private val capability = source.capabilities.single()

    @Test
    fun `shipped package opens a typed SMS draft without server access or messaging app changes`() =
        runTest {
            val identity = PackageIdentity("00000000-0000-0000-0000-000000000001")
            var launched: Intent? = null
            val host =
                object : DeclarativeHost {
                    override suspend fun unavailableReason(binding: DeclarativeBinding): String? = null

                    override suspend fun launch(request: IntentRequest): ExecutionOutcome {
                        launched = AndroidDeclarativeHost.buildIntent(request)
                        return ExecutionOutcome(InvocationStatus.HANDED_OFF, request.receipts.success!!)
                    }

                    override suspend fun query(
                        request: ContentRequest,
                        timeoutMillis: Long,
                    ): ContentRows = error("Must not query a provider")

                    override suspend fun request(
                        request: HttpRequest,
                        timeoutMillis: Long,
                    ): HttpResponse = error("Must not contact a server")
                }
            assertTrue(source.httpBindings().isEmpty())
            val adapter =
                PackageAdapter({ listOf(LoadedPackage(identity, source, true)) }, { host }, BoundedExecution(backgroundScope)) { _, _, p ->
                    WaitBudget(p.interactionMode, 30_000, null, null)
                }
            val disk =
                object : ExtensionGrantPersistence {
                    var json: String? = null

                    override suspend fun read(): String? = json

                    override suspend fun write(json: String) {
                        this.json = json
                    }
                }
            val registry = CapabilityRegistry(emptyMap())
            val runtime = ExtensionRuntime(registry, adapter, ExtensionGrants(disk), backgroundScope)
            runCurrent()
            val key =
                runtime.settings.value.entries
                    .single()
                    .key
            assertFalse(
                runtime.settings.value.entries
                    .single()
                    .enabled,
            )
            runtime.enable(key, true)
            runCurrent()
            assertTrue(registry.catalog.isEmpty())
            runtime.mutation(key, "compose", true)
            runCurrent()
            val definition = registry.catalog.single()
            val repository = MemoryInvocationRepository()
            val receipt =
                CapabilityDispatcher(registry, repository).execute(
                    ToolProposal(
                        "draft",
                        definition.id,
                        mapOf("recipient" to "+12025550123", "message" to "Hello & goodbye?"),
                        "Prepare draft",
                        registry.snapshot.revision,
                    ),
                )
            assertEquals(InvocationStatus.HANDED_OFF, receipt.status)
            assertTrue(receipt.message.contains("tap Send"))
            assertEquals("Messages", receipt.provenance!!.source.title)
            assertEquals(receipt, repository.history().single())
            val intent = requireNotNull(launched)
            assertEquals(Intent.ACTION_SENDTO, intent.action)
            assertEquals("smsto", intent.data!!.scheme)
            assertEquals("+12025550123", intent.data!!.schemeSpecificPart)
            assertEquals("Hello & goodbye?", intent.getStringExtra("sms_body"))
            assertNull(intent.component)
            assertNull(intent.`package`)
            assertEquals(0, intent.flags)
        }

    @Test
    fun `phone validator and opaque slot reject injected destinations and preserve fixed schemes`() {
        val binding = capability.binding as DeclarativeBinding.Intent
        listOf("Alice", "+12025550123;12025550124", "+12025550123?body=hidden", "smsto:+12025550123").forEach { number ->
            assertThrows(IllegalArgumentException::class.java) {
                BindingArguments(capability, mapOf("recipient" to number, "message" to "Test")).intent(binding)
            }
        }
        listOf("https://example.test/", "intent:", "file:", "content:").forEach { base ->
            assertThrows(IllegalArgumentException::class.java) {
                PackageCodec.decode(json.replace("\"base\": \"smsto:\"", "\"base\": \"$base\""))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PackageCodec.decode(json.replace("\"base\": \"smsto:\"", "\"query\": {}, \"base\": \"smsto:\""))
        }
    }
}
