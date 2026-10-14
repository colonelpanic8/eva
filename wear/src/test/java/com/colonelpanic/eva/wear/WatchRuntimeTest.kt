package com.colonelpanic.eva.wear

import android.app.Application
import com.colonelpanic.eva.diagnostics.EvaTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class WatchRuntimeTest {
    @Test fun `older snapshots cannot reenable sensing after newer disable`() {
        val context = RuntimeEnvironment.getApplication()
        val runtime = WatchRuntime(context)
        val disabled = WearGestureConfiguration(requiredPulses = 6, maxGestureMillis = 1600, peakRate = 7.0)
        runtime.apply(WearProtocol.encode(WatchConfiguration(20, disabled)), "phone")
        runtime.apply(WearProtocol.encode(WatchConfiguration(10, disabled.copy(enabled = true))), "phone")
        assertEquals(disabled, runtime.config)
        assertEquals(6, runtime.state.value.requiredPulses)
        assertFalse(runtime.state.value.enabled)
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun `local pause survives process state recreation and phone sync until explicitly resumed`() {
        val context = RuntimeEnvironment.getApplication()
        val runtime = WatchRuntime(context)
        runtime.pause()
        val enabled = WearGestureConfiguration(enabled = true, requiredPulses = 6, maxGestureMillis = 1600)
        runtime.apply(WearProtocol.encode(WatchConfiguration(20, enabled)), "phone")
        val restored = WatchRuntime(context)
        assertEquals(enabled, restored.config)
        assertTrue(restored.paused)
        assertFalse(restored.state.value.enabled)
        restored.reconcile()
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun `late requested receipt cannot replace an actual connected outcome`() {
        val runtime = WatchRuntime(RuntimeEnvironment.getApplication())
        runtime.apply(WearProtocol.encode(WatchConfiguration(1, WearGestureConfiguration())), "phone")
        runtime.result("phone", WatchStartResult("trigger", "connected", "Voice connected"))
        runtime.result("phone", WatchStartResult("trigger", "requested", "Waiting for voice"))
        assertEquals("Voice connected", runtime.state.value.status)
    }
}
