package com.colonelpanic.eva.conversation

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = TurnWorkServiceTest.WorkApplication::class)
class TurnWorkServiceTest {
    class WorkApplication :
        Application(),
        TurnWorkHost {
        var needed = true
        val interruptions = mutableListOf<String>()

        override fun needsWorkCoverage() = needed

        override fun interruptWork(reason: String) {
            interruptions += reason
        }
    }

    @Test
    fun `timeout renews coverage without waiting for a working state change`() {
        val host = RuntimeEnvironment.getApplication() as WorkApplication
        val controller = Robolectric.buildService(TurnWorkService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(null, 0, 1)
            val shadow = shadowOf(service)
            val firstNotification = shadow.lastForegroundNotification
            service.onTimeout(1)
            assertNotSame(firstNotification, shadow.lastForegroundNotification)
            assertFalse(shadow.isStoppedBySelf)
            assertFalse(shadow.isForegroundStopped)
            assertTrue(host.interruptions.isEmpty())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `Android refusing renewal interrupts dependent work and stops the timed out service`() {
        val host = RuntimeEnvironment.getApplication() as WorkApplication
        val controller = Robolectric.buildService(TurnWorkService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(null, 0, 1)
            val shadow = shadowOf(service)
            shadow.setThrowInStartForeground(IllegalStateException("Foreground start refused"))
            service.onTimeout(1)
            assertEquals(1, host.interruptions.size)
            assertTrue(host.interruptions.single().contains("Android did not allow"))
            assertTrue(shadow.isStoppedBySelf)
            assertTrue(shadow.isForegroundStopped)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `timeout after work finishes stops coverage without interrupting another turn`() {
        val host = RuntimeEnvironment.getApplication() as WorkApplication
        val controller = Robolectric.buildService(TurnWorkService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(null, 0, 1)
            host.needed = false
            service.onTimeout(1)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertTrue(host.interruptions.isEmpty())
        } finally {
            controller.destroy()
        }
    }
}
