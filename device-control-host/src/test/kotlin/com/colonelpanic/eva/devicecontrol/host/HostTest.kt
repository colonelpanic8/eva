package com.colonelpanic.eva.devicecontrol.host

import com.colonelpanic.eva.devicecontrol.proto.ActionResult
import com.colonelpanic.eva.devicecontrol.proto.ExecutionStatus
import com.colonelpanic.eva.devicecontrol.proto.Observation
import com.colonelpanic.eva.devicecontrol.proto.ProtocolJson
import com.colonelpanic.eva.devicecontrol.proto.kind
import com.colonelpanic.eva.devicecontrol.testing.FakeDeviceBackend
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

private fun spec(json: String) = Json.parseToJsonElement(json).jsonObject

private fun resource(name: String) = requireNotNull(HostTest::class.java.getResource("/$name")).readText()

private fun screen() = ProtocolJson.decodeFromString(Observation.serializer(), resource("observation.json"))

private fun backend() = FakeDeviceBackend(screen()) { action -> ActionResult(action.actionId, action.kind, true, "start", "end", screen()) }

private class FakeAdb : Adb {
    val calls = mutableListOf<List<String>>()
    var respond: (List<String>) -> String = { "" }

    override suspend fun command(args: List<String>): String {
        calls += args
        return respond(args)
    }
}

class HostTest {
    @Test fun loadsAllThreeFamiliesAndRejectsUnknownHelpers() {
        val root = Path.of(requireNotNull(javaClass.getResource("/cases")).toURI())
        val cases = loadCases(root, setOf("settings", "chrome_read", "media"))
        assertEquals(12, cases.size)
        assertEquals(1, cases.count { it.emulatorExclusion != null })
        val bad = cases.first().raw.toMutableMap()
        bad["typo"] = JsonPrimitive(true)
        assertThrows(IllegalArgumentException::class.java) { EvalCase(JsonObject(bad)) }
        assertThrows(
            IllegalStateException::class.java,
        ) { validateStep(spec("""{"helper":"clear_app_data","package":"com.android.chrome"}""")) }
        assertThrows(IllegalArgumentException::class.java) { validateStep(spec("""{"helper":"wait","seconds":61}""")) }
        assertThrows(IllegalArgumentException::class.java) { validateStep(spec("""{"helper":"open_url","url":"a","fixture":"b"}""")) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { validateStep(spec("""{"helper":"force_stop","package":"com.mobilerun.portal"}""")) }
    }

    @Test fun resetMapsCommandsQuotesArgumentsAndRestoresFirstSnapshot() =
        runBlocking {
            val adb = FakeAdb()
            val sleeps = mutableListOf<Long>()
            adb.respond = { args ->
                val command = args.last()
                when {
                    "resolve-activity" in command -> "com.android.launcher3/.Launcher"
                    "'dumpsys'" in command -> "topResumedActivity=ActivityRecord{abc u0 com.android.launcher3/.Launcher t1}"
                    "'settings' 'get'" in command -> "null"
                    else -> ""
                }
            }
            val store = SnapshotStore(Files.createTempDirectory("host-snapshot").resolve("state.json"))
            val reset = DeviceReset(adb, DeviceProbes(adb, backend()), "http://10.0.2.2:49123", store, sleep = { sleeps += it })
            reset.step(spec("""{"helper":"snapshot_setting","namespace":"secure","key":"ui_night_mode"}"""))
            store.remember("settings/secure/ui_night_mode", "2")
            reset.step(spec("""{"helper":"set_setting","namespace":"secure","key":"ui_night_mode","value":"2"}"""))
            reset.step(spec("""{"helper":"restore_setting","namespace":"secure","key":"ui_night_mode"}"""))
            reset.step(spec("""{"helper":"force_stop","package":"com.android.settings"}"""))
            reset.step(spec("""{"helper":"go_home"}"""))
            reset.step(spec("""{"helper":"wait","seconds":0.25}"""))
            adb.shell("am", "start", "-d", "https://example.org/?a='x'&b=$(bad)")
            val commands = adb.calls.map { it.last() }
            assertTrue("'cmd' 'uimode' 'night' 'yes'" in commands)
            assertTrue("'cmd' 'uimode' 'night' 'no'" in commands)
            assertTrue("'settings' 'delete' 'secure' 'ui_night_mode'" in commands)
            assertTrue("'am' 'force-stop' 'com.android.settings'" in commands)
            assertTrue("'input' 'keyevent' 'KEYCODE_HOME'" in commands)
            assertEquals(listOf(250L), sleeps)
            assertTrue(commands.last().contains("'\"'\"'"))
            assertTrue(store.pending().isEmpty())
        }

    @Test fun launchOpenUrlAndMediaWaitForIndependentAdbState() =
        runBlocking {
            val adb = FakeAdb()
            adb.respond = { args ->
                when {
                    "resolve-activity" in args.last() -> {
                        "com.android.chrome/.Main"
                    }

                    "'activity'" in args.last() -> {
                        "topResumedActivity=ActivityRecord{abc u0 com.android.chrome/.Main t1}"
                    }

                    "media_session" in args.last() -> {
                        "  package=com.android.chrome\n  active=true\n  state=PlaybackState {state=PLAYING(3)}"
                    }

                    else -> {
                        ""
                    }
                }
            }
            val reset = DeviceReset(adb, DeviceProbes(adb, backend()), "http://10.0.2.2:49123/", SnapshotStore())
            reset.step(spec("""{"helper":"launch_app","package":"com.android.chrome"}"""))
            reset.step(spec("""{"helper":"launch_app","package":"com.android.chrome","activity":".Main"}"""))
            reset.step(spec("""{"helper":"launch_app","package":"com.android.chrome","action":"android.intent.action.VIEW"}"""))
            reset.step(spec("""{"helper":"open_url","fixture":"library-hours.html"}"""))
            reset.step(spec("""{"helper":"media_key","key":"play","package":"com.android.chrome"}"""))
            assertTrue(adb.calls.any { it.last() == "'am' 'start' '-W' '-n' 'com.android.chrome/.Main'" })
            assertTrue(adb.calls.any { "'http://10.0.2.2:49123/library-hours.html'" in it.last() })
            assertTrue(adb.calls.any { it.last() == "'input' 'keyevent' 'KEYCODE_MEDIA_PLAY'" })
        }

    @Test fun checkersUseFreshObservationsAndRejectStaleForeground() =
        runBlocking {
            val adb = FakeAdb()
            adb.respond = { "topResumedActivity=ActivityRecord{abc u0 org.example.messages/.ConversationActivity t1}" }
            val backend = backend()
            val checkers = Checkers(DeviceProbes(adb, backend, agreementMillis = 0))
            assertTrue(
                checkers
                    .check(
                        spec("""{"name":"foreground_activity","package":"org.example.messages","activity":".ConversationActivity"}"""),
                        null,
                    ).passed,
            )
            assertTrue(checkers.check(spec("""{"name":"element_text_present","text":"mOm"}"""), null).passed)
            assertFalse(checkers.check(spec("""{"name":"element_text_present","text":"mOm","exact":true}"""), null).passed)
            assertTrue(checkers.check(spec("""{"name":"element_text_absent","text":"Never here"}"""), null).passed)
            assertEquals(4, backend.observations)
            adb.respond = { "topResumedActivity=ActivityRecord{abc u0 com.android.settings/.Settings t1}" }
            try {
                checkers.check(spec("""{"name":"foreground_package","package":"org.example.messages"}"""), null)
                fail("stale tree accepted")
            } catch (
                _: IllegalStateException,
            ) {
            }
        }

    @Test fun answerGroupsSettingsMediaAndVolumeHaveIndependentVerdicts() =
        runBlocking {
            val adb = FakeAdb()
            adb.respond = { args ->
                when {
                    "media_session' 'volume" in args.last() -> "[v] volume is 8 in range [0..15]"
                    "media_session" in args.last() -> resource("media-session.txt")
                    else -> "2"
                }
            }
            val checks = Checkers(DeviceProbes(adb, backend()))
            val answer = spec("""{"name":"answer_contains","includes":[["7 pm","seven"],["Hillcrest"]],"excludes":["nine"]}""")
            assertTrue(checks.check(answer, "HILLCREST closes at 7   PM").passed)
            assertFalse(checks.check(answer, "Hillcrest closes at seven, not nine").passed)
            assertFalse(checks.check(answer, null).passed)
            assertTrue(
                checks.check(spec("""{"name":"settings_value","namespace":"secure","key":"ui_night_mode","equals":"2"}"""), null).passed,
            )
            assertTrue(
                checks
                    .check(
                        spec(
                            """{"name":"media_playback","package":"org.example.player","state":"playing","title_contains":"paper kites"}""",
                        ),
                        null,
                    ).passed,
            )
            assertFalse(checks.check(spec("""{"name":"media_playback","package":"org.example.player","state":"paused"}"""), null).passed)
            assertTrue(checks.check(spec("""{"name":"stream_volume","stream":"music","at_least":1,"at_most":11}"""), null).passed)
        }

    @Test fun recordedLauncherHasChromeAndForegroundMatches() =
        runBlocking {
            val observation = ProtocolJson.decodeFromString(Observation.serializer(), resource("launcher-recorded.json"))
            val backend = FakeDeviceBackend(observation) { error("checker must not mutate") }
            val adb = FakeAdb()
            adb.respond = {
                "topResumedActivity=ActivityRecord{abc u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t1}"
            }
            val checks = Checkers(DeviceProbes(adb, backend, agreementMillis = 0))
            val text = spec("""{"name":"element_text_present","text":"Chrome","exact":true}""")
            val foreground = spec("""{"name":"foreground_package","package":"com.google.android.apps.nexuslauncher"}""")
            assertTrue(checks.check(text, null).passed)
            assertTrue(checks.check(foreground, null).passed)
            assertEquals(2, backend.observations)
        }

    @Test fun scriptedDriverStopsOnUncertainActionWithoutRetry() =
        runBlocking {
            val backend = backend()
            backend.onPerform =
                { action ->
                    ActionResult(
                        action.actionId,
                        action.kind,
                        false,
                        "start",
                        "end",
                        null,
                        executionStatus = ExecutionStatus.OUTCOME_UNKNOWN,
                    )
                }
            val agent = ScriptedAgent(backend, spec("""{"actions":[{"kind":"home"},{"kind":"home"}],"answer":"done"}"""), 3)
            assertEquals("UNKNOWN", agent.run("goal") {}.status.name)
            assertEquals(1, backend.actions.size)
        }
}

class RunnerTest {
    private fun case(): EvalCase {
        val original = loadCase(Path.of(requireNotNull(javaClass.getResource("/cases/settings/wifi_scanning_off.yaml")).toURI()))
        val raw = original.raw.toMutableMap()
        raw["reset"] =
            Json.parseToJsonElement(
                """[
            {"helper":"snapshot_setting","namespace":"global","key":"wifi_scan_always_enabled"},
            {"helper":"set_setting","namespace":"global","key":"wifi_scan_always_enabled","value":"1"}
        ]""",
            )
        raw["teardown"] =
            Json.parseToJsonElement(
                """[
            {"helper":"restore_setting","namespace":"global","key":"wifi_scan_always_enabled"}
        ]""",
            )
        return EvalCase(JsonObject(raw))
    }

    @Test fun noopRecordsFailureAndRestoresSettingAfterChecker() =
        runBlocking {
            val adb = FakeAdb()
            var setting = "0"
            adb.respond = { args ->
                when {
                    "'get'" in args.last() -> {
                        setting
                    }

                    "'put'" in args.last() -> {
                        setting = args.last().substringAfterLast("enabled' '").removeSuffix("'")
                        ""
                    }

                    else -> {
                        ""
                    }
                }
            }
            val probes = DeviceProbes(adb, backend())
            val reset = DeviceReset(adb, probes, null, SnapshotStore())
            val record = EvalRunner(reset, Checkers(probes), true).run(case(), "noop", NoopAgent())
            assertEquals("failed", record.outcome)
            assertFalse(requireNotNull(record.checkerVerdict).passed)
            assertEquals("0", setting)
            assertEquals(0, record.taskSteps)
            assertTrue(record.steps.any { it.phase == "teardown" && it.outcome == "completed" })
            val output = Files.createTempDirectory("eva-record").resolve("run.jsonl")
            appendRecord(output, record)
            assertEquals(1, Files.readAllLines(output).size)
            assertEquals("failed", Json.parseToJsonElement(Files.readString(output)).jsonObject.text("outcome"))
        }

    @Test fun resetFailureStillRestoresAndRecordsNoTask() =
        runBlocking {
            val adb = FakeAdb()
            var fail = true
            adb.respond = { args ->
                if ("'get'" in args.last()) {
                    "0"
                } else if ("'put'" in args.last() && fail) {
                    fail = false
                    error("reset failed")
                } else {
                    ""
                }
            }
            val probes = DeviceProbes(adb, backend())
            val reset = DeviceReset(adb, probes, null, SnapshotStore())
            val record = EvalRunner(reset, Checkers(probes), true).run(case(), "noop", NoopAgent())
            assertEquals("reset_error", record.outcome)
            assertEquals(null, record.checkerVerdict)
            assertTrue(reset.snapshots.pending().isEmpty())
            assertTrue(record.steps.none { it.phase == "task" })
        }
}
