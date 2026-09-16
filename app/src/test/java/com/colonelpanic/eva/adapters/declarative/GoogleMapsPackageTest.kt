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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.URLDecoder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GoogleMapsPackageTest {
    private val default = DefaultPackages.all.single { it.id == "android.google-maps" }
    private val json =
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "app/src/main/assets/${default.path}") }
            .first { it.isFile }
            .readText()
    private val source = PackageCodec.decode(json)

    @Test
    fun `shipped default matches its catalog listing and a fixed installation identity`() {
        assertEquals(default.id, source.id)
        assertEquals("https://github.com/colonelpanic8/eva-extensions.git", default.source)
        assertEquals("packages/google-maps.json", default.path)
        assertEquals(default.identity, DefaultPackages.all.single().identity)
        assertEquals(listOf("com.google.android.apps.maps"), source.androidPackages)
        assertEquals(listOf("search", "navigate"), source.capabilities.map { it.name })
    }

    @Test
    fun `adopted package exposes both map actions and keeps arguments inside fixed intent slots`() =
        runTest {
            val launched = mutableListOf<Intent>()
            val host =
                object : DeclarativeHost {
                    override suspend fun unavailableReason(binding: DeclarativeBinding): String? = null

                    override suspend fun launch(request: IntentRequest): ExecutionOutcome {
                        launched += AndroidDeclarativeHost.buildIntent(request)
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
            val adapter =
                PackageAdapter(
                    { listOf(LoadedPackage(default.identity, source, true)) },
                    { host },
                    BoundedExecution(backgroundScope),
                ) { _, _, p ->
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
            assertTrue(registry.catalog.isEmpty())
            assertTrue(runtime.adopt(default.identity))
            runCurrent()
            val entry =
                runtime.settings.value.entries
                    .single()
            assertTrue(entry.enabled)
            assertEquals(setOf("search", "navigate"), entry.mutations)
            val prefix = "extension.package.${default.identity.id}."
            assertEquals(listOf("search", "navigate").map { prefix + it }.toSet(), registry.catalog.map { it.id }.toSet())

            val dispatcher = CapabilityDispatcher(registry, MemoryInvocationRepository())
            var calls = 0

            suspend fun execute(
                name: String,
                arguments: Map<String, String>,
            ) = dispatcher.execute(ToolProposal("call:${calls++}", prefix + name, arguments, name, registry.snapshot.revision))

            val destination = "Ferry Building &mode=w#x"
            assertEquals(InvocationStatus.HANDED_OFF, execute("search", mapOf("destination" to destination)).status)
            assertEquals(InvocationStatus.HANDED_OFF, execute("navigate", mapOf("destination" to destination)).status)
            assertEquals(
                InvocationStatus.HANDED_OFF,
                execute("navigate", mapOf("destination" to destination, "travelmode" to "bicycling")).status,
            )
            assertEquals(
                InvocationStatus.NOT_EXECUTED,
                execute("navigate", mapOf("destination" to destination, "travelmode" to "b")).status,
            )

            val (search, driving, bicycling) = launched
            assertEquals(3, launched.size)
            assertEquals(Intent.ACTION_VIEW, search.action)
            assertEquals("geo", search.data!!.scheme)
            assertEquals(destination, URLDecoder.decode(search.data.toString().substringAfter("?q="), "UTF-8"))
            assertEquals(1, search.data.toString().count { it == '?' })

            for ((intent, mode) in listOf(driving to "d", bicycling to "b")) {
                assertEquals(Intent.ACTION_VIEW, intent.action)
                assertEquals("google.navigation", intent.data!!.scheme)
                val parts = intent.data!!.encodedSchemeSpecificPart.split('&')
                assertEquals(2, parts.size)
                assertEquals(destination, URLDecoder.decode(parts[0].removePrefix("q="), "UTF-8"))
                assertEquals("mode=$mode", parts[1])
            }
            launched.forEach {
                assertNull(it.component)
                assertNull(it.`package`)
                assertEquals(0, it.flags)
            }
        }
}
