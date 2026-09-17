package com.colonelpanic.eva.adapters.declarative

import android.app.Application
import android.content.Intent
import android.provider.AlarmClock
import com.colonelpanic.eva.adapters.android.AndroidDeclarativeHost
import com.colonelpanic.eva.capability.BoundedExecution
import com.colonelpanic.eva.capability.BundledCapabilities
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClockPackageTest {
    private val default = DefaultPackages.all.single { it.id == "android.clock" }
    private val maps = DefaultPackages.all.single { it.id == "android.google-maps" }
    private val json = asset(default)
    private val source = PackageCodec.decode(json)
    private val prefix = "extension.package.${default.identity.id}."

    private fun asset(default: DefaultPackage): String =
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "app/src/main/assets/${default.path}") }
            .first { it.isFile }
            .readText()

    private class Disk : ExtensionGrantPersistence {
        var json: String? = null

        override suspend fun read(): String? = json

        override suspend fun write(json: String) {
            this.json = json
        }
    }

    private class Host : DeclarativeHost {
        val launched = mutableListOf<Intent>()

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

    private fun TestScope.runtime(
        packages: List<LoadedPackage>,
        host: Host,
        disk: Disk,
    ): Pair<CapabilityRegistry, ExtensionRuntime> {
        val adapter =
            PackageAdapter({ packages }, { host }, BoundedExecution(backgroundScope)) { _, _, p ->
                WaitBudget(p.interactionMode, 30_000, null, null)
            }
        val registry = CapabilityRegistry(emptyMap())
        return registry to ExtensionRuntime(registry, adapter, ExtensionGrants(disk), backgroundScope)
    }

    @Test
    fun `shipped default matches its catalog listing and replaces the removed native clock actions`() {
        assertEquals(default.id, source.id)
        assertEquals("https://github.com/colonelpanic8/eva-extensions.git", default.source)
        assertEquals("packages/clock.json", default.path)
        assertTrue(source.androidPackages.isEmpty())
        assertEquals(listOf("set_alarm", "set_timer"), source.capabilities.map { it.name })
        source.capabilities.forEach { capability ->
            val binding = capability.binding as DeclarativeBinding.Intent
            val skipUi = binding.extras.getValue(AlarmClock.EXTRA_SKIP_UI) as ScalarSlot.Literal
            assertEquals(false, skipUi.value.booleanOrNull)
        }
        assertEquals(AlarmClock.ACTION_SET_ALARM, (source.capabilities[0].binding as DeclarativeBinding.Intent).action)
        assertEquals(AlarmClock.ACTION_SET_TIMER, (source.capabilities[1].binding as DeclarativeBinding.Intent).action)
        val bundled = BundledCapabilities.definitions.map { it.id }
        assertTrue(bundled.none { it.contains("alarm") || it.contains("timer") })
    }

    @Test
    fun `adopted package hands alarms and timers to the standard clock intents with typed extras`() =
        runTest {
            val host = Host()
            val disk = Disk()
            val (registry, runtime) = runtime(listOf(LoadedPackage(default.identity, source, true)), host, disk)
            runCurrent()
            assertTrue(registry.catalog.isEmpty())
            assertTrue(runtime.adopt(default.identity))
            runCurrent()
            val entry =
                runtime.settings.value.entries
                    .single()
            assertTrue(entry.enabled)
            assertEquals(setOf("set_alarm", "set_timer"), entry.mutations)
            assertEquals(listOf("set_alarm", "set_timer").map { prefix + it }.toSet(), registry.catalog.map { it.id }.toSet())

            val repository = MemoryInvocationRepository()
            val dispatcher = CapabilityDispatcher(registry, repository)
            var calls = 0

            suspend fun execute(
                name: String,
                arguments: Map<String, String>,
            ) = dispatcher.execute(ToolProposal("call:${calls++}", prefix + name, arguments, name, registry.snapshot.revision))

            val label = "Café ☕ réveil — 起床"
            val alarm = execute("set_alarm", mapOf("hour" to "7", "minute" to "30", "label" to label))
            assertEquals(InvocationStatus.HANDED_OFF, alarm.status)
            assertTrue(alarm.message.contains("not verified"))
            assertEquals("Clock", alarm.provenance!!.source.title)
            assertEquals(InvocationStatus.HANDED_OFF, execute("set_alarm", mapOf("hour" to "0", "minute" to "0")).status)
            assertEquals(InvocationStatus.HANDED_OFF, execute("set_alarm", mapOf("hour" to "23", "minute" to "59")).status)
            val timer = execute("set_timer", mapOf("seconds" to "600", "label" to label))
            assertEquals(InvocationStatus.HANDED_OFF, timer.status)
            assertTrue(timer.message.contains("not verified"))
            assertEquals(InvocationStatus.HANDED_OFF, execute("set_timer", mapOf("seconds" to "1")).status)
            assertEquals(InvocationStatus.HANDED_OFF, execute("set_timer", mapOf("seconds" to "86400")).status)
            assertEquals(6, repository.history().size)
            assertEquals(6, host.launched.size)

            val (labelled, midnight, lastMinute) = host.launched.take(3)
            val (tea, shortest, longest) = host.launched.drop(3)
            assertEquals(AlarmClock.ACTION_SET_ALARM, labelled.action)
            assertEquals(7, labelled.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
            assertEquals(30, labelled.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
            assertEquals(label, labelled.getStringExtra(AlarmClock.EXTRA_MESSAGE))
            assertEquals(false, labelled.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true))
            assertEquals(0, midnight.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
            assertEquals(0, midnight.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
            assertFalse(midnight.hasExtra(AlarmClock.EXTRA_MESSAGE))
            assertEquals(23, lastMinute.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
            assertEquals(59, lastMinute.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))

            assertEquals(AlarmClock.ACTION_SET_TIMER, tea.action)
            assertEquals(600, tea.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
            assertEquals(label, tea.getStringExtra(AlarmClock.EXTRA_MESSAGE))
            assertEquals(false, tea.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true))
            assertEquals(1, shortest.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
            assertFalse(shortest.hasExtra(AlarmClock.EXTRA_MESSAGE))
            assertEquals(86400, longest.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
            host.launched.forEach {
                assertNull(it.data)
                assertNull(it.component)
                assertNull(it.`package`)
                assertEquals(0, it.flags)
                assertTrue(it.extras!!.keySet().all { key -> key.startsWith("android.intent.extra.alarm.") })
            }
        }

    @Test
    fun `malformed clock requests are refused before any intent is built`() =
        runTest {
            val host = Host()
            val (registry, runtime) = runtime(listOf(LoadedPackage(default.identity, source, true)), host, Disk())
            runCurrent()
            assertTrue(runtime.adopt(default.identity))
            runCurrent()
            val repository = MemoryInvocationRepository()
            val dispatcher = CapabilityDispatcher(registry, repository)
            val rejected =
                listOf(
                    "set_alarm" to mapOf("hour" to "7"),
                    "set_alarm" to mapOf("minute" to "30"),
                    "set_alarm" to mapOf("hour" to "24", "minute" to "0"),
                    "set_alarm" to mapOf("hour" to "-1", "minute" to "0"),
                    "set_alarm" to mapOf("hour" to "7", "minute" to "60"),
                    "set_alarm" to mapOf("hour" to "seven", "minute" to "30"),
                    "set_alarm" to mapOf("hour" to "7.5", "minute" to "30"),
                    "set_alarm" to mapOf("hour" to "7", "minute" to "30", "label" to ""),
                    "set_alarm" to mapOf("hour" to "7", "minute" to "30", "label" to "x".repeat(121)),
                    "set_alarm" to mapOf("hour" to "7", "minute" to "30", "seconds" to "5"),
                    "set_alarm" to mapOf("hour" to "7", "minute" to "30", "android.intent.extra.alarm.SKIP_UI" to "true"),
                    "set_timer" to emptyMap(),
                    "set_timer" to mapOf("seconds" to "0"),
                    "set_timer" to mapOf("seconds" to "86401"),
                    "set_timer" to mapOf("seconds" to "ten minutes"),
                    "set_timer" to mapOf("seconds" to "600", "hour" to "7"),
                )
            rejected.forEachIndexed { index, (name, arguments) ->
                val receipt =
                    dispatcher.execute(ToolProposal("call:$index", prefix + name, arguments, name, registry.snapshot.revision))
                assertEquals("$name $arguments", InvocationStatus.NOT_EXECUTED, receipt.status)
            }
            assertTrue(host.launched.isEmpty())
            assertEquals(rejected.size, repository.history().size)
            assertTrue(repository.history().all { it.status == InvocationStatus.NOT_EXECUTED })
        }

    @Test
    fun `disabling the package or one of its actions withholds the clock without touching the other action`() =
        runTest {
            val host = Host()
            val disk = Disk()
            val (registry, runtime) = runtime(listOf(LoadedPackage(default.identity, source, true)), host, disk)
            runCurrent()
            assertTrue(runtime.adopt(default.identity))
            runCurrent()
            val key =
                runtime.settings.value.entries
                    .single()
                    .key
            val dispatcher = CapabilityDispatcher(registry, MemoryInvocationRepository())

            suspend fun execute(
                name: String,
                arguments: Map<String, String>,
                revision: String = registry.snapshot.revision,
            ) = dispatcher.execute(ToolProposal("call:$name:${arguments.hashCode()}:$revision", prefix + name, arguments, name, revision))

            val timerRevision = registry.snapshot.revision
            runtime.mutation(key, "set_timer", false)
            runCurrent()
            assertEquals(
                setOf("set_alarm"),
                runtime.settings.value.entries
                    .single()
                    .mutations,
            )
            assertEquals(listOf(prefix + "set_alarm"), registry.catalog.map { it.id })
            assertEquals(InvocationStatus.NOT_EXECUTED, execute("set_timer", mapOf("seconds" to "600"), timerRevision).status)
            assertEquals(InvocationStatus.NOT_EXECUTED, execute("set_timer", mapOf("seconds" to "600")).status)
            assertEquals(InvocationStatus.HANDED_OFF, execute("set_alarm", mapOf("hour" to "6", "minute" to "15")).status)
            assertEquals(1, host.launched.size)

            runtime.enable(key, false)
            runCurrent()
            assertFalse(
                runtime.settings.value.entries
                    .single()
                    .enabled,
            )
            assertTrue(registry.catalog.isEmpty())
            assertEquals(InvocationStatus.NOT_EXECUTED, execute("set_alarm", mapOf("hour" to "6", "minute" to "15")).status)
            assertEquals(1, host.launched.size)

            val restarted = runtime(listOf(LoadedPackage(default.identity, source, true)), host, disk)
            runCurrent()
            assertFalse(
                restarted.second.settings.value.entries
                    .single()
                    .enabled,
            )
            assertTrue(restarted.first.catalog.isEmpty())
        }

    @Test
    fun `clock joins a configuration that already adopted maps and its grants survive a restart`() =
        runTest {
            val mapsSource = PackageCodec.decode(asset(maps))
            val host = Host()
            val disk = Disk()
            val (registry, runtime) = runtime(listOf(LoadedPackage(maps.identity, mapsSource, true)), host, disk)
            runCurrent()
            assertTrue(runtime.adopt(maps.identity))
            runCurrent()
            val mapsGrant = runtime.portableGrants().getValue(maps.identity.instanceId)
            assertEquals(setOf("search", "navigate"), mapsGrant.mutations)

            val both = listOf(LoadedPackage(maps.identity, mapsSource, true), LoadedPackage(default.identity, source, true))
            val upgraded = runtime(both, host, disk)
            runCurrent()
            val settings =
                upgraded.second.settings.value.entries
                    .associateBy { it.installed.identity }
            assertTrue(settings.getValue(maps.identity).enabled)
            assertFalse(settings.getValue(default.identity).enabled)
            assertEquals(2, upgraded.first.catalog.size)

            assertTrue(upgraded.second.adopt(default.identity))
            runCurrent()
            assertEquals(mapsGrant, upgraded.second.portableGrants().getValue(maps.identity.instanceId))
            assertEquals(
                setOf("set_alarm", "set_timer"),
                upgraded.second
                    .portableGrants()
                    .getValue(default.identity.instanceId)
                    .mutations,
            )
            assertEquals(4, upgraded.first.catalog.size)

            val restarted = runtime(both, host, disk)
            runCurrent()
            assertTrue(
                restarted.second.settings.value.entries
                    .all { it.enabled },
            )
            assertEquals(4, restarted.first.catalog.size)
            assertEquals(mapsGrant, restarted.second.portableGrants().getValue(maps.identity.instanceId))

            val removed = runtime(listOf(LoadedPackage(maps.identity, mapsSource, true)), host, disk)
            runCurrent()
            assertEquals(
                listOf(maps.identity),
                removed.second.settings.value.entries
                    .map { it.installed.identity },
            )
            assertEquals(2, removed.first.catalog.size)
            assertEquals(mapsGrant, removed.second.portableGrants().getValue(maps.identity.instanceId))
        }
}
