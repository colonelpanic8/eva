package com.colonelpanic.eva.devicecontrol

import android.app.UiAutomation
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colonelpanic.eva.devicecontrol.portal.PortalBackend
import com.colonelpanic.eva.devicecontrol.portal.PortalClient
import com.colonelpanic.eva.devicecontrol.proto.Action
import com.colonelpanic.eva.devicecontrol.proto.ActionKind
import com.colonelpanic.eva.devicecontrol.proto.ActionResult
import com.colonelpanic.eva.devicecontrol.proto.ActivateElement
import com.colonelpanic.eva.devicecontrol.proto.Back
import com.colonelpanic.eva.devicecontrol.proto.Element
import com.colonelpanic.eva.devicecontrol.proto.ExecutionStatus
import com.colonelpanic.eva.devicecontrol.proto.Home
import com.colonelpanic.eva.devicecontrol.proto.ImeAction
import com.colonelpanic.eva.devicecontrol.proto.ImeActionDetails
import com.colonelpanic.eva.devicecontrol.proto.ImeActionName
import com.colonelpanic.eva.devicecontrol.proto.LaunchApp
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
import com.colonelpanic.eva.devicecontrol.proto.Swipe
import com.colonelpanic.eva.devicecontrol.proto.TapPoint
import com.colonelpanic.eva.devicecontrol.proto.kind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PortalBackendDeviceTest {
    @Test fun everyRequiredActionRunsThroughSamePhonePortal() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Opt-in dedicated emulator only", args.getString("evaPortalParity") == "true")
            assumeTrue(
                Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator"),
            )
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val token =
                ParcelFileDescriptor
                    .AutoCloseInputStream(
                        automation.executeShellCommand("cat /data/local/tmp/eva-portal-token"),
                    ).bufferedReader()
                    .use {
                        it.readText().trim()
                    }
            check(token.isNotBlank()) { "Provision the emulator's Portal token before running this test." }
            val fixturePackage = instrumentation.context.packageName
            val backend =
                PortalBackend(PortalClient(token = { token }), timing = { t ->
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString(
                                "stream",
                                "EVA_PORTAL_TIMING ${t.kind} recheck=${t.recheckMillis} http=${t.httpMillis} settle=${t.settleMillis} polls=${t.polls} settled=${t.settled}\n",
                            )
                        },
                    )
                })
            val kinds = mutableSetOf<ActionKind>()
            var count = 0

            suspend fun perform(make: (String, String) -> Action): ActionResult {
                val before = backend.observe()
                val action = make("device-${++count}", before.observationId)
                val result = backend.perform(action)
                assertTrue("${action.kind}: ${result.error?.javaClass?.simpleName}", result.ok)
                assertEquals(ExecutionStatus.EXECUTED, result.executionStatus)
                kinds += action.kind
                return result
            }

            suspend fun launchFixture() = perform { id, ref -> LaunchApp(id, "parity", 0, ref, fixturePackage) }

            suspend fun element(label: String): Element =
                backend.observe().elements.first { it.text == label || it.contentDescription == label }

            suspend fun assertText(label: String) {
                assertTrue("Expected $label", backend.observe().elements.any { it.text == label })
            }

            launchFixture()
            val button = element("Activate fixture")
            perform { id, ref -> ActivateElement(id, "parity", 0, ref, button.index) }
            assertText("Activated")
            val tap = element("Activate fixture")
            perform { id, ref ->
                TapPoint(
                    id,
                    "parity",
                    0,
                    ref,
                    (tap.bounds.left + tap.bounds.right) / 2,
                    (tap.bounds.top + tap.bounds.bottom) / 2,
                    tap.index,
                )
            }
            assertText("Activated")
            val hold = element("Activate fixture")
            perform { id, ref -> LongPress(id, "parity", 0, ref, hold.index) }
            assertText("Long pressed")

            var input = element("Fixture input")
            val typed = perform { id, ref -> SetText(id, "parity", 0, ref, input.index, "café 🎉") }
            assertEquals("café 🎉", (typed.details as SetTextDetails).actual)
            input = element("Fixture input")
            val appended = perform { id, ref -> SetText(id, "parity", 0, ref, input.index, "!", replace = false) }
            assertEquals("café 🎉!", (appended.details as SetTextDetails).actual)
            input = element("Fixture input")
            val ime = perform { id, ref -> ImeAction(id, "parity", 0, ref, ImeActionName.SEARCH, input.index) }
            assertEquals("enter", (ime.details as ImeActionDetails).performedAs)
            assertText("Search submitted")
            val password = element("Fixture password")
            val secret = perform { id, ref -> SetText(id, "parity", 0, ref, password.index, "fixture-only-secret") }
            assertEquals(SetTextDetails(password.index, true, null, null), secret.details)
            assertFalse(ProtocolJson.encodeToString(ActionResult.serializer(), secret).contains("fixture-only-secret"))
            perform { id, ref -> Back(id, "parity", 0, ref) }

            launchFixture()
            val beforeScroll = backend.observe()
            val scrollable = beforeScroll.elements.first { it.scrollable }
            val scroll = perform { id, ref -> Scroll(id, "parity", 0, ref, ScrollDirection.DOWN, scrollable.index) }
            assertFalse(beforeScroll.elements.map { it.text } == scroll.observation!!.elements.map { it.text })
            val container = backend.observe().elements.first { it.scrollable }
            val b = container.bounds
            val x = (b.left + b.right) / 2
            val swipe =
                perform { id, ref ->
                    Swipe(
                        id,
                        "parity",
                        0,
                        ref,
                        Point(x, b.top + (b.bottom - b.top) * 3 / 4),
                        Point(
                            x,
                            b.top + (b.bottom - b.top) / 4,
                        ),
                        container.index,
                    )
                }
            assertFalse(scroll.observation!!.elements.map { it.text } == swipe.observation!!.elements.map { it.text })
            val screenshot = perform { id, ref -> Screenshot(id, "parity", 0, ref) }
            assertTrue((screenshot.details as ScreenshotDetails).png.isNotBlank())
            perform { id, ref -> Home(id, "parity", 0, ref) }
            assertFalse(backend.observe().packageName == fixturePackage)
            launchFixture()
            perform { id, ref -> OpenUrl(id, "parity", 0, ref, "http://eva-device-test.invalid/fixture", fixturePackage) }
            assertText("URL opened")
            perform { id, ref -> OpenNotifications(id, "parity", 0, ref) }
            assertEquals("com.android.systemui", backend.observe().packageName)
            perform { id, ref -> Back(id, "parity", 0, ref) }
            assertEquals(ActionKind.entries.toSet() - ActionKind.LOCK_SCREEN, kinds)
        }
}
