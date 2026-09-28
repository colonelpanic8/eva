@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.colonelpanic.eva.devicecontrol.portal

import com.colonelpanic.eva.devicecontrol.proto.ActionKind
import com.colonelpanic.eva.devicecontrol.proto.ActionResult
import com.colonelpanic.eva.devicecontrol.proto.ActivateElement
import com.colonelpanic.eva.devicecontrol.proto.AppNotFound
import com.colonelpanic.eva.devicecontrol.proto.Back
import com.colonelpanic.eva.devicecontrol.proto.DuplicateAction
import com.colonelpanic.eva.devicecontrol.proto.ExecutionStatus
import com.colonelpanic.eva.devicecontrol.proto.Home
import com.colonelpanic.eva.devicecontrol.proto.ImeAction
import com.colonelpanic.eva.devicecontrol.proto.ImeActionDetails
import com.colonelpanic.eva.devicecontrol.proto.ImeActionName
import com.colonelpanic.eva.devicecontrol.proto.LaunchApp
import com.colonelpanic.eva.devicecontrol.proto.LockScreen
import com.colonelpanic.eva.devicecontrol.proto.LongPress
import com.colonelpanic.eva.devicecontrol.proto.OpenNotifications
import com.colonelpanic.eva.devicecontrol.proto.OpenUrl
import com.colonelpanic.eva.devicecontrol.proto.Point
import com.colonelpanic.eva.devicecontrol.proto.ProtocolJson
import com.colonelpanic.eva.devicecontrol.proto.Screenshot
import com.colonelpanic.eva.devicecontrol.proto.ScreenshotDetails
import com.colonelpanic.eva.devicecontrol.proto.Scroll
import com.colonelpanic.eva.devicecontrol.proto.ScrollDirection
import com.colonelpanic.eva.devicecontrol.proto.SetText
import com.colonelpanic.eva.devicecontrol.proto.SetTextDetails
import com.colonelpanic.eva.devicecontrol.proto.StaleObservation
import com.colonelpanic.eva.devicecontrol.proto.Swipe
import com.colonelpanic.eva.devicecontrol.proto.TapPoint
import com.colonelpanic.eva.devicecontrol.proto.TextMismatch
import com.colonelpanic.eva.devicecontrol.proto.kind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Base64

class PortalBackendTest {
    private class Phone : PortalTransport {
        var label = "Title"
        var text = ""
        var password = false
        var focused = true
        var packageName = "org.example.app"
        var mismatch = false
        var fail = false
        var degraded = 0
        var afterCommand: (() -> Unit)? = null
        val commands = mutableListOf<PortalCommand>()

        override suspend fun state(): JsonObject {
            if (degraded-- > 0) throw DegradedSnapshot()
            return stateOf(label, text, password, focused, packageName)
        }

        override suspend fun command(command: PortalCommand) {
            commands += command
            if (fail) throw IOException("lost reply")
            if (command.method == "keyboard/input" && !mismatch) {
                text =
                    Base64
                        .getDecoder()
                        .decode(
                            command.params
                                .getValue("base64_text")
                                .jsonPrimitive.content,
                        ).toString(Charsets.UTF_8)
            }
            if (command.method == "tap") focused = true
            afterCommand?.invoke()
        }

        override suspend fun screenshot() = PNG
    }

    @Test fun rechecksTargetAndRejectsStaleIdentityWithoutInput() =
        runTest {
            val phone = Phone()
            val backend = PortalBackend(phone, clock = { testScheduler.currentTime })
            val before = backend.observe()
            phone.label = "Changed"
            val result = backend.perform(ActivateElement("a", "task", 0, before.observationId, 0))
            assertTrue(result.error is StaleObservation)
            assertEquals(ExecutionStatus.NOT_DISPATCHED, result.executionStatus)
            assertTrue(phone.commands.isEmpty())
        }

    @Test fun replacesAndAppendsUnicodeWithReadBack() =
        runTest {
            val phone = Phone()
            val backend = PortalBackend(phone, clock = { testScheduler.currentTime })
            val before = backend.observe()
            val result = backend.perform(SetText("a", "task", 0, before.observationId, 1, "café 🎉"))
            assertTrue(result.ok)
            assertEquals(SetTextDetails(1, true, "café 🎉", "café 🎉"), result.details)
            val appended = backend.perform(SetText("b", "task", 0, result.observation!!.observationId, 1, "!", replace = false))
            assertEquals("café 🎉!", (appended.details as SetTextDetails).actual)
        }

    @Test fun passwordReadBackAndMismatchNeverExposePassword() =
        runTest {
            val phone =
                Phone().apply {
                    password = true
                    text = "old"
                }
            val backend = PortalBackend(phone, clock = { testScheduler.currentTime })
            val before = backend.observe()
            assertNull(before.elements[1].text)
            val result = backend.perform(SetText("a", "task", 0, before.observationId, 1, "private"))
            assertEquals(SetTextDetails(1, true, null, null), result.details)
            assertFalse(ProtocolJson.encodeToString(ActionResult.serializer(), result).contains("private"))
            phone.mismatch = true
            val mismatch = backend.perform(SetText("b", "task", 0, result.observation!!.observationId, 1, "new-private"))
            assertFalse(mismatch.ok)
            assertEquals(null, (mismatch.error as TextMismatch).actual)
            assertEquals(null, (mismatch.error as TextMismatch).expected)
        }

    @Test fun waitsForFocusBeforeTypingAndAcceptsMaskedReadBack() =
        runTest {
            val phone =
                Phone().apply {
                    focused = false
                    password = true
                    afterCommand = { text = "••" }
                }
            val backend = PortalBackend(phone, clock = { testScheduler.currentTime })
            val before = backend.observe()
            val result = backend.perform(SetText("a", "task", 0, before.observationId, 1, "🎉"))
            assertTrue(result.ok)
            assertEquals(listOf("tap", "keyboard/input"), phone.commands.map { it.method })
        }

    @Test
    fun redactsAFieldThatBecomesPasswordProtectedAfterFocus() =
        runTest {
            val phone = Phone().apply { focused = false }
            phone.afterCommand = { phone.password = true }
            val backend = PortalBackend(phone, clock = { testScheduler.currentTime })
            val before = backend.observe()
            val result = backend.perform(SetText("a", "task", 0, before.observationId, 1, "private"))
            assertTrue(result.ok)
            assertEquals(SetTextDetails(1, true, null, null), result.details)
        }

    @Test fun uncertainMutationIsNotRetriedAndDuplicateIdCannotRun() =
        runTest {
            val phone = Phone().apply { fail = true }
            val backend = PortalBackend(phone, clock = { testScheduler.currentTime })
            val before = backend.observe()
            val action = Back("a", "task", 0, before.observationId)
            val result = backend.perform(action)
            assertEquals(ExecutionStatus.OUTCOME_UNKNOWN, result.executionStatus)
            assertEquals(1, phone.commands.size)
            assertTrue(backend.perform(action).error is DuplicateAction)
            assertEquals(1, phone.commands.size)
        }

    @Test fun launchMustReachRequestedPackageAndSettleReportsItsBudget() =
        runTest {
            val phone = Phone()
            val timings = mutableListOf<ActionTiming>()
            val backend = PortalBackend(phone, clock = { testScheduler.currentTime }, timing = timings::add)
            val before = backend.observe()
            val missing = backend.perform(LaunchApp("a", "task", 0, before.observationId, "org.example.missing"))
            assertFalse(missing.ok)
            assertTrue(missing.error is AppNotFound)
            assertTrue(missing.unsettled)
            assertTrue(timings.last().settleMillis >= 6000)
            phone.afterCommand = { phone.packageName = "org.example.target" }
            val success = backend.perform(LaunchApp("b", "task", 0, missing.observation!!.observationId, "org.example.target"))
            assertTrue(success.ok)
            assertFalse(success.unsettled)
        }

    @Test fun retriesOnlyDegradedReadsAndRecordsScreenshotDimensions() =
        runTest {
            val phone = Phone().apply { degraded = 2 }
            val backend = PortalBackend(phone, clock = { testScheduler.currentTime })
            val before = backend.observe()
            assertEquals(200L, testScheduler.currentTime)
            val result = backend.perform(Screenshot("a", "task", 0, before.observationId))
            assertTrue(result.ok)
            val details = result.details as ScreenshotDetails
            assertEquals(1, details.width)
            assertEquals(1, details.height)
            assertEquals(result.observation!!.observationId, details.observationId)
        }

    @Test fun plansEveryProtocolActionAndImeRunsAsEnter() =
        runTest {
            val phone = Phone()
            val backend =
                PortalBackend(phone, policy = SettlePolicy(quietMillis = 0, changeGraceMillis = 0), clock = { testScheduler.currentTime })
            val seen = mutableSetOf<ActionKind>()
            for (kind in ActionKind.entries) {
                val before = backend.observe()
                val id = kind.name
                val ref = before.observationId
                val action =
                    when (kind) {
                        ActionKind.LAUNCH_APP -> LaunchApp(id, "t", 0, ref, phone.packageName)
                        ActionKind.ACTIVATE_ELEMENT -> ActivateElement(id, "t", 0, ref, 0)
                        ActionKind.SET_TEXT -> SetText(id, "t", 0, ref, 1, "hello")
                        ActionKind.SCROLL -> Scroll(id, "t", 0, ref, ScrollDirection.DOWN, 2)
                        ActionKind.BACK -> Back(id, "t", 0, ref)
                        ActionKind.HOME -> Home(id, "t", 0, ref)
                        ActionKind.LOCK_SCREEN -> LockScreen(id, "t", 0, ref)
                        ActionKind.TAP_POINT -> TapPoint(id, "t", 0, ref, 5, 5, 0)
                        ActionKind.SWIPE -> Swipe(id, "t", 0, ref, Point(5, 70), Point(5, 50), 2)
                        ActionKind.LONG_PRESS -> LongPress(id, "t", 0, ref, 0)
                        ActionKind.SCREENSHOT -> Screenshot(id, "t", 0, ref)
                        ActionKind.IME_ACTION -> ImeAction(id, "t", 0, ref, ImeActionName.SEARCH, 1)
                        ActionKind.OPEN_URL -> OpenUrl(id, "t", 0, ref, "https://example.org")
                        ActionKind.OPEN_NOTIFICATIONS -> OpenNotifications(id, "t", 0, ref)
                    }
                val result = backend.perform(action)
                assertTrue("$kind: ${result.error}", result.ok)
                if (kind == ActionKind.IME_ACTION) {
                    assertEquals(ImeActionDetails(ImeActionName.SEARCH), result.details)
                    assertEquals(
                        66,
                        phone.commands
                            .last()
                            .params
                            .getValue("key_code")
                            .jsonPrimitive.int,
                    )
                }
                seen += result.kind
            }
            assertEquals(ActionKind.entries.toSet(), seen)
        }

    @Test
    fun cancellationDuringFocusWaitsForInFlightInputAndNeverTypesAfterwards() =
        runTest {
            val phone = Phone().apply { focused = false }
            val issued = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val transport =
                object : PortalTransport by phone {
                    override suspend fun command(command: PortalCommand) {
                        phone.command(command)
                        issued.complete(Unit)
                        release.await()
                    }
                }
            val backend = PortalBackend(transport, clock = { testScheduler.currentTime })
            val before = backend.observe()
            val task = launch { backend.perform(SetText("a", "task", 0, before.observationId, 1, "never typed")) }
            issued.await()
            task.cancel()
            runCurrent()
            assertFalse(task.isCompleted)
            release.complete(Unit)
            task.join()
            assertEquals(listOf("tap"), phone.commands.map { it.method })
        }

    companion object {
        val PNG: ByteArray =
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aL1sAAAAASUVORK5CYII=",
            )

        fun stateOf(
            label: String = "Title",
            text: String = "",
            password: Boolean = false,
            focused: Boolean = true,
            pkg: String = "org.example.app",
        ): JsonObject =
            buildJsonObject {
                put("phone_state", buildJsonObject { put("packageName", pkg) })
                put(
                    "device_context",
                    buildJsonObject {
                        put(
                            "screen_bounds",
                            buildJsonObject {
                                put("width", 100)
                                put("height", 200)
                            },
                        )
                    },
                )
                put(
                    "a11y_tree",
                    buildJsonObject {
                        put(
                            "children",
                            JsonArray(
                                listOf(
                                    node(label, "Button", 0, 20, clickable = true, longClickable = true),
                                    node(text, "EditText", 20, 40, editable = true, focused = focused, password = password),
                                    node("list", "ScrollView", 40, 200, scrollable = true),
                                ),
                            ),
                        )
                    },
                )
            }

        private fun node(
            text: String,
            cls: String,
            top: Int,
            bottom: Int,
            clickable: Boolean = false,
            longClickable: Boolean = false,
            editable: Boolean = false,
            focused: Boolean = false,
            password: Boolean = false,
            scrollable: Boolean = false,
        ) = buildJsonObject {
            put("text", text)
            put("className", cls)
            put("resourceId", "org.example.app:id/$cls")
            put("isClickable", clickable)
            put("isLongClickable", longClickable)
            put("isEditable", editable)
            put("isFocused", focused)
            put("isPassword", password)
            put("isScrollable", scrollable)
            put(
                "boundsInScreen",
                buildJsonObject {
                    put("left", 0)
                    put("top", top)
                    put("right", 100)
                    put("bottom", bottom)
                },
            )
        }
    }
}
