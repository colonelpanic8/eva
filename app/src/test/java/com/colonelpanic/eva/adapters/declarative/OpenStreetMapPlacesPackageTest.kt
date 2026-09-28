package com.colonelpanic.eva.adapters.declarative

import android.app.Application
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OpenStreetMapPlacesPackageTest {
    private val default = DefaultPackages.all.single { it.id == "openstreetmap.places" }
    private val source =
        PackageCodec.decode(
            generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
                .map { File(it, "app/src/main/assets/${default.path}") }
                .first { it.isFile }
                .readText(),
        )

    @Test
    fun `nearby keeps arguments in fixed Nominatim query slots and lists each place with its address`() =
        runTest {
            val requested = mutableListOf<HttpRequest>()
            val host =
                object : DeclarativeHost {
                    override suspend fun unavailableReason(binding: DeclarativeBinding): String? = null

                    override suspend fun launch(request: IntentRequest): ExecutionOutcome = error("Must not open an app")

                    override suspend fun query(
                        request: ContentRequest,
                        timeoutMillis: Long,
                    ): ContentRows = error("Must not query a provider")

                    override suspend fun request(
                        request: HttpRequest,
                        timeoutMillis: Long,
                    ): HttpResponse {
                        requested += request
                        return HttpResponse(200, PHARMACIES)
                    }
                }
            val adapter =
                PackageAdapter(
                    { listOf(LoadedPackage(default.identity, source, true)) },
                    { host },
                    BoundedExecution(backgroundScope),
                ) { _, _, p -> WaitBudget(p.interactionMode, 30_000, null, null) }
            val disk =
                object : ExtensionGrantPersistence {
                    var json: String? = null

                    override suspend fun read(): String? = json

                    override suspend fun write(json: String) {
                        this.json = json
                    }
                }
            val registry = CapabilityRegistry(emptyMap())
            ExtensionRuntime(registry, adapter, ExtensionGrants(disk), backgroundScope).adopt(default.identity)
            runCurrent()
            val id = "extension.package.${default.identity.id}.nearby"
            assertEquals(listOf(id), registry.catalog.map { it.id })

            val outcome =
                CapabilityDispatcher(registry, MemoryInvocationRepository()).execute(
                    ToolProposal(
                        "call:0",
                        id,
                        mapOf("query" to "pharmacy&bounded=0", "viewbox" to "-122.464,37.7526,-122.414,37.7926"),
                        "nearby",
                        registry.snapshot.revision,
                    ),
                )

            assertEquals(InvocationStatus.COMPLETED, outcome.status)
            val url = requested.single().url.toHttpUrl()
            assertEquals("nominatim.openstreetmap.org", url.host)
            assertEquals("pharmacy&bounded=0", url.queryParameter("q"))
            assertEquals(listOf("1"), url.queryParameterValues("bounded"))
            assertEquals("-122.464,37.7526,-122.414,37.7926", url.queryParameter("viewbox"))
            assertTrue(outcome.message, outcome.message.contains("\"CVS Pharmacy, 499, Haight Street, San Francisco, 94117\""))
            assertTrue(outcome.message.contains("opening_hours=\"Mo-Fr 09:00-19:00\" phone=null"))
        }

    private companion object {
        /** Trimmed from a real `format=jsonv2&extratags=1` response. */
        const val PHARMACIES =
            """[{"osm_type":"node","osm_id":2718062345,"lat":"37.7720316","lon":"-122.4302850","category":"amenity",
            "type":"pharmacy","name":"CVS Pharmacy","display_name":"CVS Pharmacy, 499, Haight Street, San Francisco, 94117",
            "extratags":{"opening_hours":"Mo-Fr 09:00-19:00"}},{"osm_type":"way","osm_id":265434502,"lat":"37.7644051",
            "lon":"-122.4523355","category":"amenity","type":"pharmacy","name":"Walgreens",
            "display_name":"Walgreens, 199, Parnassus Avenue, San Francisco, 94117","extratags":null}]"""
    }
}
