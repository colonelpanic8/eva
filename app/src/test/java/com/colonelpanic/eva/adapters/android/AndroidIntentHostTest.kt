package com.colonelpanic.eva.adapters.android

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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AndroidIntentHostTest {
    @Test
    fun `a resumed activity takes precedence and a paused activity falls back to the assistant`() =
        runBlocking {
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val host = AndroidIntentHost()
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
            val host = AndroidIntentHost()
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
            val host = AndroidIntentHost()
            host.attachAssistant { throw IllegalStateException("Cannot start assistant activity on a hidden session") }
            assertEquals(InvocationStatus.NOT_EXECUTED, host.launch(Intent(), "Opened", "Missing").status)
        }
}
