package com.colonelpanic.eva.conversation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.colonelpanic.eva.ForegroundServiceGate
import com.colonelpanic.eva.MainActivity
import kotlinx.coroutines.flow.first

interface TurnWorkHost {
    fun needsWorkCoverage(): Boolean

    fun interruptWork(reason: String)

    fun workTasks(): List<TaskSnapshot> = emptyList()

    fun workCoverageChanged(coverage: WorkCoverage) {}

    fun workCoverageLimited(reason: String) {}

    fun stopAllWork() {}
}

class TurnWorkService : Service() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var coverage = WorkCoverage.NONE
    private val updateNotification =
        object : Runnable {
            override fun run() {
                if (coverage == WorkCoverage.NONE) return
                runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) }
                handler.postDelayed(this, 1_000)
            }
        }
    private val host get() = application as? TurnWorkHost

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (coverage == WorkCoverage.NONE && !promote()) {
            foregroundRejected()
            return START_NOT_STICKY
        }
        if (intent?.action == UPGRADE && coverage == WorkCoverage.SHORT_SERVICE) tryPromote(WorkCoverage.LONG_RUNNING)
        promotion.value = coverage
        if (intent?.action == STOP_ALL) host?.stopAllWork()
        if (gate.foregrounded()) stopCoverage()
        handler.removeCallbacks(updateNotification)
        if (coverage != WorkCoverage.NONE) handler.post(updateNotification)
        return START_NOT_STICKY
    }

    internal fun notification(): Notification = WorkNotifications.running(this, host?.workTasks().orEmpty(), coverage)

    private fun promote(): Boolean {
        WorkNotifications.channels(this)
        if (tryPromote(WorkCoverage.LONG_RUNNING)) return true
        if (Build.VERSION.SDK_INT >= 34 && tryPromote(WorkCoverage.SHORT_SERVICE)) {
            host?.workCoverageLimited(SHORT_LIMIT)
            return true
        }
        return false
    }

    private fun tryPromote(mode: WorkCoverage): Boolean {
        val notice = WorkNotifications.running(this, host?.workTasks().orEmpty(), mode)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val type =
                    if (mode == WorkCoverage.SHORT_SERVICE) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    }
                startForeground(NOTIFICATION_ID, notice, type)
            } else {
                startForeground(NOTIFICATION_ID, notice)
            }
        } catch (_: SecurityException) {
            return false
        } catch (_: IllegalStateException) {
            return false
        }
        coverage = mode
        host?.workCoverageChanged(mode)
        promotion.value = mode
        return true
    }

    private fun foregroundRejected() {
        gate.startRejected()
        promotion.value = WorkCoverage.NONE
        host?.workCoverageChanged(WorkCoverage.NONE)
        host?.workCoverageLimited(START_DENIED)
        host?.interruptWork(START_DENIED)
        stopCoverage()
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateNotification)
        val unexpectedlyLost = coverage != WorkCoverage.NONE && promotion.value != null && host?.needsWorkCoverage() == true
        coverage = WorkCoverage.NONE
        if (promotion.value != null) promotion.value = WorkCoverage.NONE
        host?.workCoverageChanged(WorkCoverage.NONE)
        gate.destroyed()
        if (unexpectedlyLost) {
            host?.interruptWork(
                "Android stopped EVA's background work service. Partial findings are retained in this thread.",
            )
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int) = timedOut()

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) = timedOut()

    private fun timedOut() {
        if (host?.needsWorkCoverage() == true) {
            if (tryPromote(WorkCoverage.LONG_RUNNING)) return
            host?.workCoverageLimited(START_DENIED)
            host?.interruptWork("$START_DENIED Partial findings are retained in this thread.")
        }
        stopCoverage()
    }

    private fun stopCoverage() {
        coverage = WorkCoverage.NONE
        promotion.value = WorkCoverage.NONE
        host?.workCoverageChanged(WorkCoverage.NONE)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val START_DENIED = "Android did not allow EVA to continue this request in the background."
        internal const val SHORT_LIMIT =
            "Android refused long-running coverage. Background work has only about three minutes from service promotion."
        internal const val UPGRADE = "com.colonelpanic.eva.UPGRADE_WORK"
        internal const val STOP_ALL = "com.colonelpanic.eva.STOP_ALL_WORK"
        private const val NOTIFICATION_ID = 42
        private val gate = ForegroundServiceGate()
        private val promotion = kotlinx.coroutines.flow.MutableStateFlow<WorkCoverage?>(WorkCoverage.NONE)

        fun retryUpgrade(context: Context) {
            if (promotion.value == WorkCoverage.LONG_RUNNING || promotion.value == null) return
            if ((context.applicationContext as? TurnWorkHost)?.needsWorkCoverage() != true) return
            start(context, upgrade = true)
        }

        fun start(
            context: Context,
            upgrade: Boolean = false,
        ) {
            val previous = promotion.value
            promotion.value = null
            if (!gate.requestStart {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, TurnWorkService::class.java).apply {
                            if (upgrade) {
                                action =
                                    UPGRADE
                            }
                        },
                    )
                }
            ) {
                if (upgrade && previous == WorkCoverage.SHORT_SERVICE) {
                    promotion.value = previous
                    return
                }
                promotion.value = WorkCoverage.NONE
                (context.applicationContext as? TurnWorkHost)?.let {
                    it.workCoverageLimited(START_DENIED)
                    it.interruptWork(START_DENIED)
                }
            }
        }

        suspend fun ensureStarted(context: Context): Boolean {
            if (promotion.value == WorkCoverage.NONE) start(context)
            val mode = promotion.first { it != null }
            if (mode == WorkCoverage.SHORT_SERVICE) (context.applicationContext as? TurnWorkHost)?.workCoverageLimited(SHORT_LIMIT)
            return mode != WorkCoverage.NONE
        }

        fun stop(context: Context) {
            if (gate.stopping()) {
                promotion.value = WorkCoverage.NONE
                context.stopService(Intent(context, TurnWorkService::class.java))
            }
        }
    }
}

/** Notifications for work that finished with nobody watching. */
object WorkNotifications {
    const val WORK_CHANNEL = "eva.work"
    const val EXTRA_RUNNING_WORK = "com.colonelpanic.eva.RUNNING_WORK"
    const val EXTRA_THREAD_ID = "com.colonelpanic.eva.THREAD_ID"

    private val limitNotices = LimitNoticeCoalescer()

    fun limited(
        context: Context,
        reason: String,
    ) {
        if (!limitNotices.shouldNotify(reason, android.os.SystemClock.elapsedRealtime())) return
        channels(context)
        val notification =
            NotificationCompat
                .Builder(context, WORK_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Background work restricted by Android")
                .setOnlyAlertOnce(true)
                .setContentText(reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
                .setContentIntent(open(context, null))
                .setAutoCancel(true)
                .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(43, notification) }
    }

    fun running(
        context: Context,
        tasks: List<TaskSnapshot>,
        coverage: WorkCoverage,
    ): Notification {
        val top = tasks.firstOrNull { it.looksStuck } ?: tasks.firstOrNull()
        val elapsed = top?.let { ((System.currentTimeMillis() - it.startedAt).coerceAtLeast(0) / 60_000) } ?: 0
        val details =
            listOfNotNull(
                top?.let { "${it.request} · ${elapsed}m elapsed" },
                "Looks stuck".takeIf { tasks.any { it.looksStuck } },
                TurnWorkService.SHORT_LIMIT.takeIf { coverage == WorkCoverage.SHORT_SERVICE },
            ).joinToString(" · ")
        return NotificationCompat
            .Builder(context, WORK_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(
                when (tasks.size) {
                    0 -> "EVA background work ready"
                    1 -> "EVA is working on 1 task"
                    else -> "EVA is working on ${tasks.size} tasks"
                },
            ).setContentText(details)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open(context, null))
            .addAction(
                0,
                "Stop all",
                PendingIntent.getService(
                    context,
                    0,
                    Intent(context, TurnWorkService::class.java).setAction(TurnWorkService.STOP_ALL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun channels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(WORK_CHANNEL, "Requests finishing in the background", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Shown while EVA finishes a request after a call ended, and when the answer is ready"
            },
        )
    }

    fun open(
        context: Context,
        threadId: String?,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            threadId?.hashCode() ?: 0,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (threadId == null) putExtra(EXTRA_RUNNING_WORK, true) else putExtra(EXTRA_THREAD_ID, threadId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun answered(
        context: Context,
        answer: ThreadController.BackgroundAnswer,
    ) {
        channels(context)
        val notification: Notification =
            NotificationCompat
                .Builder(context, WORK_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(
                    if (answer.status ==
                        TurnStatus.ANSWERED
                    ) {
                        answer.title
                    } else {
                        "${answer.title} · ${answer.status.name.lowercase()}"
                    },
                ).setContentText(answer.answer.lineSequence().firstOrNull { it.isNotBlank() } ?: "Finished.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(answer.answer))
                .setAutoCancel(true)
                .setContentIntent(open(context, answer.threadId))
                .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(answer.threadId.hashCode(), notification) }
    }
}

internal class LimitNoticeCoalescer {
    private var lastReason: String? = null
    private var lastAt = 0L

    fun shouldNotify(
        reason: String,
        now: Long,
    ): Boolean {
        if (lastReason == reason && now - lastAt < 60_000) return false
        lastReason = reason
        lastAt = now
        return true
    }
}
