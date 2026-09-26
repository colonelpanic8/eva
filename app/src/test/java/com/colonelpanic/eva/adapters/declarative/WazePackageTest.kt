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
class WazePackageTest {
    private val default = DefaultPackages.all.single { it.id == "android.waze" }
    private val json =
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "app/src/main/assets/${default.path}") }
            .first { it.isFile }
            .readText()
    private val source = PackageCodec.decode(json)

    @Test
    fun `navigation goes to exact coordinates when given and otherwise searches, pinned to Waze`() =
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
            assertEquals(setOf("navigate", "navigate_saved"), entry.mutations)
            val prefix = "extension.package.${default.identity.id}."
            assertEquals(listOf("navigate", "navigate_saved").map { prefix + it }.toSet(), registry.catalog.map { it.id }.toSet())

            val dispatcher = CapabilityDispatcher(registry, MemoryInvocationRepository())
            var calls = 0

            suspend fun execute(
                name: String,
                arguments: Map<String, String>,
            ) = dispatcher.execute(ToolProposal("call:${calls++}", prefix + name, arguments, name, registry.snapshot.revision))

            val destination = "Ferry Building &navigate=no#x"
            assertEquals(InvocationStatus.HANDED_OFF, execute("navigate", mapOf("destination" to destination)).status)
            assertEquals(
                InvocationStatus.HANDED_OFF,
                execute(
                    "navigate",
                    mapOf("destination" to destination, "coordinates" to "37.7955,-122.3937", "avoid_tolls" to "true"),
                ).status,
            )
            assertEquals(InvocationStatus.HANDED_OFF, execute("navigate_saved", mapOf("place" to "home")).status)
            assertEquals(InvocationStatus.NOT_EXECUTED, execute("navigate_saved", mapOf("place" to "gym")).status)

            fun Intent.query() =
                data!!.encodedQuery!!.split('&').associate {
                    val (name, value) = it.split('=', limit = 2)
                    name to URLDecoder.decode(value, "UTF-8")
                }
            assertEquals(3, launched.size)
            val (search, exact, saved) = launched
            assertEquals(mapOf("q" to destination, "navigate" to "yes"), search.query())
            assertEquals(mapOf("ll" to "37.7955,-122.3937", "navigate" to "yes", "avoid_tolls" to "true"), exact.query())
            assertEquals(mapOf("favorite" to "home", "navigate" to "yes"), saved.query())
            launched.forEach {
                assertEquals(Intent.ACTION_VIEW, it.action)
                assertEquals("https://waze.com/ul", "${it.data!!.scheme}://${it.data!!.host}${it.data!!.path}")
                assertEquals("com.waze", it.`package`)
            }
        }
}
