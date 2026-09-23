package com.colonelpanic.eva.adapters.android

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AndroidIntentHostTest {
    @Test
    fun `assistant can use existing permissions without an activity and cannot grant missing ones`() =
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            val host = AndroidIntentHost(app)
            host.attachAssistant {}
            shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_SMS)
            assertEquals(true, host.ensurePermission(Manifest.permission.READ_CONTACTS))
            assertEquals(true, host.ensurePermission(Manifest.permission.READ_SMS))
            assertEquals(null, host.permissionUnavailableReason(Manifest.permission.READ_CONTACTS))
            shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
            assertEquals(false, host.ensurePermission(Manifest.permission.READ_CONTACTS))
            assertNotNull(host.permissionUnavailableReason(Manifest.permission.READ_CONTACTS))
        }

    @Test
    fun `a resumed activity takes precedence and a paused activity falls back to the assistant`() =
        runBlocking {
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val host = AndroidIntentHost(RuntimeEnvironment.getApplication())
            var assistantCalls = 0
            host.attach(activity.get())
            host.attachAssistant { assistantCalls++ }
            assertEquals(InvocationStatus.HANDED_OFF, host.launch(Intent("test.open"), "Opened", "Missing").status)
            assertEquals(0, assistantCalls)
            assertEquals("test.open", shadowOf(activity.get()).nextStartedActivity.action)
            activity.pause()
            assertEquals(InvocationStatus.HANDED_OFF, host.launch(Intent("test.open"), "Opened", "Missing").status)
            assertEquals(1, assistantCalls)
            activity.stop().destroy()
            Unit
        }

    @Test
    fun `detaching an old session preserves its replacement and detaching the replacement prevents dispatch`() =
        runBlocking {
            val host = AndroidIntentHost(RuntimeEnvironment.getApplication())
            var calls = 0
            val old = AssistantLauncher { error("Old session dispatched") }
            val current = AssistantLauncher { calls++ }
            host.attachAssistant(old)
            host.attachAssistant(current)
            host.detachAssistant(old)
            assertEquals(InvocationStatus.HANDED_OFF, host.launch(Intent(), "Opened", "Missing").status)
            host.detachAssistant(current)
            assertEquals(InvocationStatus.NOT_EXECUTED, host.launch(Intent(), "Opened", "Missing").status)
            assertEquals(1, calls)
            assertNotNull(host.unavailableReason())
        }

    @Test
    fun `a hidden or replaced session rejection is not reported as a handoff`() =
        runBlocking {
            val host = AndroidIntentHost(RuntimeEnvironment.getApplication())
            host.attachAssistant { throw IllegalStateException("Cannot start assistant activity on a hidden session") }
            assertEquals(InvocationStatus.NOT_EXECUTED, host.launch(Intent(), "Opened", "Missing").status)
        }

    @Test
    fun `selected assistant launches with no activity or visible panel and loses access when deselected`() =
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            var selected = true
            val host = AndroidIntentHost(app, backgroundAssistantAvailable = { selected })
            assertEquals(null, host.unavailableReason())
            assertEquals(InvocationStatus.HANDED_OFF, host.launch(Intent("test.open"), "Requested", "Missing").status)
            val launched = shadowOf(app).nextStartedActivity
            assertEquals("test.open", launched.action)
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
            selected = false
            assertEquals(InvocationStatus.NOT_EXECUTED, host.launch(Intent("test.open"), "Requested", "Missing").status)
        }

    @Test
    fun `locked assistant handoffs defer to Android and retain uncertainty about completion`() =
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            val host = AndroidIntentHost(app, backgroundAssistantAvailable = { true }, deviceLocked = { true })
            assertEquals(null, host.unavailableReason())
            val result = host.launch(Intent("test.open"), "Requested", "Missing")
            assertEquals(InvocationStatus.HANDED_OFF, result.status)
            org.junit.Assert.assertTrue(result.message.contains("unlock to view or finish"))
            org.junit.Assert.assertTrue(result.message.contains("Completion is not verified"))
            assertEquals("test.open", shadowOf(app).nextStartedActivity.action)
            shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
            assertEquals(true, host.ensurePermission(Manifest.permission.READ_CONTACTS))
        }

    @Test
    fun `Android lock rejection provides unlock guidance without claiming handoff`() =
        runBlocking {
            val host = AndroidIntentHost(RuntimeEnvironment.getApplication(), deviceLocked = { true })
            host.attachAssistant { throw SecurityException("Locked target") }
            val result = host.launch(Intent("test.open"), "Requested", "Missing")
            assertEquals(InvocationStatus.NOT_EXECUTED, result.status)
            assertEquals(AndroidIntentHost.UNLOCK_REQUIRED, result.message)
        }
}
