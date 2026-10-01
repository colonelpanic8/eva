package com.colonelpanic.eva.conversation

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import com.colonelpanic.eva.audio.VoiceSessionService
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = TurnWorkServiceTest.WorkApplication::class)
class TurnWorkServiceTest {
    class WorkApplication :
        Application(),
        TurnWorkHost {
        var needed = true
        val interruptions = mutableListOf<String>()
        val limits = mutableListOf<String>()
        var coverage = WorkCoverage.NONE
        var stopped = false

        override fun workCoverageLimited(reason: String) {
            limits += reason
        }

        override fun workCoverageChanged(coverage: WorkCoverage) {
            this.coverage = coverage
        }

        override fun stopAllWork() {
            stopped = true
        }

        override fun needsWorkCoverage() = needed

        override fun interruptWork(reason: String) {
            interruptions += reason
        }
    }

    @Implements(Service::class)
    class RejectSpecialUse : ShadowService() {
        companion object {
            var reject = true
        }

        @Implementation(minSdk = 29)
        override fun startForeground(
            id: Int,
            notification: Notification,
            foregroundServiceType: Int,
        ) {
            if (reject &&
                foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            ) {
                throw SecurityException("specialUse refused")
            }
            super.startForeground(id, notification, foregroundServiceType)
        }
    }

    @Test
    @Config(shadows = [RejectSpecialUse::class])
    fun `specialUse rejection promotes short coverage and exposes its limit`() {
        RejectSpecialUse.reject = true
        val host = RuntimeEnvironment.getApplication() as WorkApplication
        val controller = Robolectric.buildService(TurnWorkService::class.java).create()
        try {
            controller.get().onStartCommand(null, 0, 1)
            assertEquals(WorkCoverage.SHORT_SERVICE, host.coverage)
            assertTrue(host.limits.single().contains("three minutes"))
            assertTrue(host.interruptions.isEmpty())
            assertTrue(
                shadowOf(
                    controller.get(),
                ).lastForegroundNotification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("three minutes"),
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Config(shadows = [RejectSpecialUse::class])
    fun `short timeout cannot renew short coverage and stops promptly`() {
        RejectSpecialUse.reject = true
        val host = RuntimeEnvironment.getApplication() as WorkApplication
        val controller = Robolectric.buildService(TurnWorkService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(null, 0, 1)
            assertEquals(WorkCoverage.SHORT_SERVICE, host.coverage)
            service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertEquals(WorkCoverage.NONE, host.coverage)
            assertEquals(1, host.interruptions.size)
            assertTrue(host.interruptions.single().contains("Partial findings"))
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Config(shadows = [RejectSpecialUse::class])
    fun `eligible upgrade replaces short coverage without stopping work`() {
        RejectSpecialUse.reject = true
        val host = RuntimeEnvironment.getApplication() as WorkApplication
        val controller = Robolectric.buildService(TurnWorkService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(null, 0, 1)
            assertEquals(WorkCoverage.SHORT_SERVICE, host.coverage)
            RejectSpecialUse.reject = false
            TurnWorkService.retryUpgrade(host)
            val intent = shadowOf(host).nextStartedService
            assertEquals(TurnWorkService.UPGRADE, intent.action)
            service.onStartCommand(intent, 0, 2)
            assertEquals(WorkCoverage.LONG_RUNNING, host.coverage)
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertTrue(host.interruptions.isEmpty())
        } finally {
            controller.destroy()
            RejectSpecialUse.reject = true
        }
    }

    @Test
    @Config(shadows = [RejectSpecialUse::class])
    fun `rejected upgrade start preserves existing short coverage`() {
        RejectSpecialUse.reject = true
        val host = RuntimeEnvironment.getApplication() as WorkApplication
        val controller = Robolectric.buildService(TurnWorkService::class.java).create()
        try {
            controller.get().onStartCommand(null, 0, 1)
            val refusing =
                object : android.content.ContextWrapper(host) {
                    override fun startForegroundService(intent: Intent): android.content.ComponentName? =
                        throw SecurityException("Rejected")
                }
            TurnWorkService.retryUpgrade(refusing)
            assertEquals(WorkCoverage.SHORT_SERVICE, host.coverage)
            assertTrue(host.interruptions.isEmpty())
            assertTrue(kotlinx.coroutines.runBlocking { TurnWorkService.ensureStarted(host) })
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `accepting work withdraws a stop deferred behind a pending start`() {
        val host = RuntimeEnvironment.getApplication() as WorkApplication
        TurnWorkService.start(host)
        TurnWorkService.stop(host)
        val controller = Robolectric.buildService(TurnWorkService::class.java).create()
        try {
            val service = controller.get()
            val covered =
                kotlinx.coroutines.runBlocking {
                    val started =
                        async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { TurnWorkService.ensureStarted(host) }
                    service.onStartCommand(null, 0, 1)
                    service.onStartCommand(null, 0, 2)
                    started.await()
                }
            assertTrue(covered)
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertEquals(WorkCoverage.LONG_RUNNING, host.coverage)
            assertTrue(host.interruptions.isEmpty())
        } finally {
            controller.destroy()
        }
    }

    @Test fun `restriction notifications coalesce repeated reasons for one minute`() {
        val coalescer = LimitNoticeCoalescer()
        assertTrue(coalescer.shouldNotify("Denied", 0))
        assertFalse(coalescer.shouldNotify("Denied", 1))
        assertFalse(coalescer.shouldNotify("Denied", 59_999))
        assertTrue(coalescer.shouldNotify("Denied", 60_000))
    }

    @Test
    fun `notification exposes tasks stall marker navigation and stop all`() {
        val host = RuntimeEnvironment.getApplication() as WorkApplication
        val task =
            TaskSnapshot(
                "thread",
                "Research",
                "turn",
                "Find the answer",
                TaskKind.DELEGATED_TEXT_AGENT,
                TaskState.WORKING,
                System.currentTimeMillis() - 240_000,
                0,
                2,
                "Search",
                "COMPLETED",
                false,
                WorkCoverage.LONG_RUNNING,
                looksStuck = true,
            )
        val notification = WorkNotifications.running(host, listOf(task, task.copy(taskId = "second")), WorkCoverage.LONG_RUNNING)
        assertEquals("EVA is working on 2 tasks", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals(
            "EVA is working on 1 task",
            WorkNotifications.running(host, listOf(task), WorkCoverage.LONG_RUNNING).extras.getCharSequence(Notification.EXTRA_TITLE),
        )
        assertEquals(
            "EVA background work ready",
            WorkNotifications.running(host, emptyList(), WorkCoverage.LONG_RUNNING).extras.getCharSequence(Notification.EXTRA_TITLE),
        )
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text.contains("Find the answer"))
        assertTrue(text.contains("4m elapsed"))
        assertTrue(text.contains("Looks stuck"))
        assertTrue(shadowOf(notification.contentIntent).savedIntent.getBooleanExtra(WorkNotifications.EXTRA_RUNNING_WORK, false))
        assertEquals("Stop all", notification.actions.single().title)
        val controller = Robolectric.buildService(TurnWorkService::class.java).create()
        try {
            controller.get().onStartCommand(shadowOf(notification.actions.single().actionIntent).savedIntent, 0, 1)
            assertTrue(host.stopped)
            assertEquals(WorkCoverage.LONG_RUNNING, host.coverage)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `during a call work coverage shares the voice notification and reposts only on change`() {
        val host = RuntimeEnvironment.getApplication() as WorkApplication
        val voice =
            androidx.core.app.NotificationCompat
                .Builder(host, "eva.voice")
                .setContentTitle("EVA is listening")
                .build()
        VoiceSessionService.shown = voice
        val manager = shadowOf(host.getSystemService(NotificationManager::class.java))
        val controller = Robolectric.buildService(TurnWorkService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(null, 0, 1)
            val shadow = shadowOf(service)
            assertEquals(VoiceSessionService.NOTIFICATION_ID, shadow.lastForegroundNotificationId)
            assertSame(voice, shadow.lastForegroundNotification)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertNull(manager.getNotification(WORK_NOTIFICATION_ID))

            VoiceSessionService.shown = null
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(WORK_NOTIFICATION_ID, shadow.lastForegroundNotificationId)
            val work = manager.getNotification(WORK_NOTIFICATION_ID)
            assertEquals("EVA background work ready", work.extras.getCharSequence(Notification.EXTRA_TITLE))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertSame(work, manager.getNotification(WORK_NOTIFICATION_ID))
        } finally {
            VoiceSessionService.shown = null
            controller.destroy()
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
            service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertTrue(host.interruptions.isEmpty())
        } finally {
            controller.destroy()
        }
    }

    private companion object {
        const val WORK_NOTIFICATION_ID = 42
    }
}
