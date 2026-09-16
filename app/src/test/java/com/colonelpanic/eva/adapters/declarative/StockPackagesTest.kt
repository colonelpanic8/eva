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

/** The web, email, calendar, and settings defaults, exercised the way the runtime adopts and launches them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StockPackagesTest {
    private val assets =
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "app/src/main/assets") }
            .first { it.isDirectory }

    private class Launched {
        val intents = mutableListOf<Intent>()
        val host =
            object : DeclarativeHost {
                override suspend fun unavailableReason(binding: DeclarativeBinding): String? = null

                override suspend fun launch(request: IntentRequest): ExecutionOutcome {
                    intents += AndroidDeclarativeHost.buildIntent(request)
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
    }

    private suspend fun kotlinx.coroutines.test.TestScope.adopt(
        id: String,
        launched: Launched,
    ): Pair<CapabilityRegistry, suspend (String, Map<String, String>) -> InvocationStatus> {
        val default = DefaultPackages.all.single { it.id == id }
        val source = PackageCodec.decode(File(assets, default.path).readText())
        assertEquals(id, source.id)
        val adapter =
            PackageAdapter(
                { listOf(LoadedPackage(default.identity, source, true)) },
                { launched.host },
                BoundedExecution(backgroundScope),
            ) {
                _,
                _,
                p,
                ->
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
        assertTrue(runtime.adopt(default.identity))
        runCurrent()
        assertTrue(
            runtime.settings.value.entries
                .single()
                .enabled,
        )
        val dispatcher = CapabilityDispatcher(registry, MemoryInvocationRepository())
        var calls = 0
        val prefix = "extension.package.${default.identity.id}."
        return registry to { name, arguments ->
            dispatcher.execute(ToolProposal("call:${calls++}", prefix + name, arguments, name, registry.snapshot.revision)).status
        }
    }

    @Test
    fun `web package searches through the search intent and opens only http or https URLs as given`() =
        runTest {
            val launched = Launched()
            val (_, execute) = adopt("android.web", launched)
            assertEquals(InvocationStatus.HANDED_OFF, execute("search", mapOf("query" to "ferry schedule & fares")))
            assertEquals(InvocationStatus.HANDED_OFF, execute("open", mapOf("url" to "https://example.com/a?b=1&c=2#frag")))
            assertEquals(InvocationStatus.NOT_EXECUTED, execute("open", mapOf("url" to "javascript:alert(1)")))
            assertEquals(InvocationStatus.NOT_EXECUTED, execute("open", mapOf("url" to "intent://scan/#Intent;end")))
            assertEquals(InvocationStatus.NOT_EXECUTED, execute("open", mapOf("url" to "https://user:pw@example.com/")))
            val (search, open) = launched.intents
            assertEquals(2, launched.intents.size)
            assertEquals(Intent.ACTION_WEB_SEARCH, search.action)
            assertEquals("ferry schedule & fares", search.getStringExtra("query"))
            assertNull(search.data)
            assertEquals(Intent.ACTION_VIEW, open.action)
            assertEquals("https://example.com/a?b=1&c=2#frag", open.dataString)
            launched.intents.forEach {
                assertNull(it.`package`)
                assertNull(it.component)
                assertEquals(0, it.flags)
            }
        }

    @Test
    fun `email package addresses a mailto draft and keeps subject and body as extras`() =
        runTest {
            val launched = Launched()
            val (_, execute) = adopt("android.email", launched)
            assertEquals(
                InvocationStatus.HANDED_OFF,
                execute("compose", mapOf("recipient" to "kat@example.com", "subject" to "Rent & bills", "body" to "Line 1\nLine 2")),
            )
            assertEquals(InvocationStatus.HANDED_OFF, execute("compose", mapOf("recipient" to "kat@example.com")))
            assertEquals(InvocationStatus.NOT_EXECUTED, execute("compose", mapOf("recipient" to "kat@example.com?cc=eve@example.com")))
            val (full, bare) = launched.intents
            assertEquals(2, launched.intents.size)
            assertEquals(Intent.ACTION_SENDTO, full.action)
            assertEquals("mailto", full.data!!.scheme)
            assertEquals("kat@example.com", full.data!!.schemeSpecificPart)
            assertEquals("Rent & bills", full.getStringExtra(Intent.EXTRA_SUBJECT))
            assertEquals("Line 1\nLine 2", full.getStringExtra(Intent.EXTRA_TEXT))
            assertNull(bare.extras)
        }

    @Test
    fun `calendar package inserts into the fixed events provider with long time extras`() =
        runTest {
            val launched = Launched()
            val (_, execute) = adopt("android.calendar", launched)
            assertEquals(
                InvocationStatus.HANDED_OFF,
                execute(
                    "event",
                    mapOf(
                        "title" to "Standup",
                        "startEpochMillis" to "1789000000000",
                        "endEpochMillis" to "1789003600000",
                        "location" to "Room 4",
                    ),
                ),
            )
            assertEquals(InvocationStatus.HANDED_OFF, execute("event", mapOf("title" to "Standup")))
            assertEquals(InvocationStatus.NOT_EXECUTED, execute("event", mapOf("title" to "Standup", "startEpochMillis" to "12")))
            val (timed, untimed) = launched.intents
            assertEquals(2, launched.intents.size)
            assertEquals(Intent.ACTION_INSERT, timed.action)
            assertEquals("content://com.android.calendar/events", timed.dataString)
            assertEquals("Standup", timed.getStringExtra("title"))
            assertEquals("Room 4", timed.getStringExtra("eventLocation"))
            assertEquals(1789000000000L, timed.getLongExtra("beginTime", -1))
            assertEquals(1789003600000L, timed.getLongExtra("endTime", -1))
            assertEquals(setOf("title"), untimed.extras!!.keySet())
        }

    @Test
    fun `settings package maps a readable screen name onto the fixed settings action`() =
        runTest {
            val launched = Launched()
            val (registry, execute) = adopt("android.settings", launched)
            assertEquals(1, registry.catalog.size)
            assertEquals(InvocationStatus.HANDED_OFF, execute("open", mapOf("screen" to "wifi")))
            assertEquals(InvocationStatus.HANDED_OFF, execute("open", mapOf("screen" to "all")))
            assertEquals(InvocationStatus.NOT_EXECUTED, execute("open", mapOf("screen" to "camera")))
            assertEquals(InvocationStatus.NOT_EXECUTED, execute("open", mapOf("screen" to "android.settings.WIFI_SETTINGS")))
            val (wifi, all) = launched.intents
            assertEquals(2, launched.intents.size)
            assertEquals("android.settings.WIFI_SETTINGS", wifi.action)
            assertEquals("android.settings.SETTINGS", all.action)
            launched.intents.forEach {
                assertNull(it.data)
                assertNull(it.extras)
                assertNull(it.`package`)
            }
        }
}
