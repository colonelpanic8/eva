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
import com.colonelpanic.eva.audio.VoiceSessionService
import com.colonelpanic.eva.diagnostics.EvaTrace
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
    private var postedId = NOTIFICATION_ID
    private var postedText: Pair<String, String>? = null
    private var promotedAt = 0L
    private val updateNotification =
        object : Runnable {
            override fun run() {
                if (coverage == WorkCoverage.NONE) return
                refreshNotification()
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

    /**
     * During a call the work service holds the voice notification's ID, so only that one is shown;
     * Android keeps a shared ID posted while any foreground service of the app still uses it.
     */
    private fun refreshNotification() {
        val voice = VoiceSessionService.shown
        if (voice != null) {
            if (postedId != VoiceSessionService.NOTIFICATION_ID) runCatching { bind(VoiceSessionService.NOTIFICATION_ID, voice, coverage) }
            return
        }
        val text = WorkNotifications.runningText(host?.workTasks().orEmpty(), coverage)
        if (postedId != NOTIFICATION_ID) {
            runCatching {
                bind(NOTIFICATION_ID, WorkNotifications.running(this, text), coverage)
                postedText = text
            }
        } else if (text != postedText && android.os.SystemClock.elapsedRealtime() - promotedAt >= DEFERRED_DISPLAY_MILLIS) {
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, WorkNotifications.running(this, text)) }
            postedText = text
        }
    }

    private fun bind(
        id: Int,
        notification: Notification,
        mode: WorkCoverage,
    ) {
        if (Build.VERSION.SDK_INT >= 34) {
            val type =
                if (mode == WorkCoverage.SHORT_SERVICE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
            startForeground(id, notification, type)
        } else {
            startForeground(id, notification)
        }
        postedId = id
        postedText = null
    }

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
        val voice = VoiceSessionService.shown
        val text = WorkNotifications.runningText(host?.workTasks().orEmpty(), mode)
        try {
            if (voice != null) {
                bind(VoiceSessionService.NOTIFICATION_ID, voice, mode)
            } else {
                bind(NOTIFICATION_ID, WorkNotifications.running(this, text), mode)
                postedText = text
            }
        } catch (error: SecurityException) {
            EvaTrace.info("coverage.refused", "mode" to mode, "error" to error.javaClass.simpleName)
            return false
        } catch (error: IllegalStateException) {
            EvaTrace.info("coverage.refused", "mode" to mode, "error" to error.javaClass.simpleName)
            return false
        }
        EvaTrace.info("coverage.started", "mode" to mode)
        coverage = mode
        promotedAt = android.os.SystemClock.elapsedRealtime()
        host?.workCoverageChanged(mode)
        promotion.value = mode
        return true
    }

    private fun foregroundRejected() {
        EvaTrace.info("coverage.rejected")
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
        EvaTrace.info("coverage.destroyed", "coverage" to coverage, "unexpected" to unexpectedlyLost)
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
        EvaTrace.info("coverage.timeout", "coverage" to coverage, "needed" to host?.needsWorkCoverage())
        if (host?.needsWorkCoverage() == true) {
            if (tryPromote(WorkCoverage.LONG_RUNNING)) return
            host?.workCoverageLimited(START_DENIED)
            host?.interruptWork("$START_DENIED Partial findings are retained in this thread.")
        }
        stopCoverage()
    }

    private fun stopCoverage() {
        EvaTrace.info("coverage.stopped", "coverage" to coverage)
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

        /** A notify() inside Android's deferral window would show the notification early. */
        private const val DEFERRED_DISPLAY_MILLIS = 10_000L
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
                EvaTrace.info("coverage.start_denied", "upgrade" to upgrade, "previous" to previous)
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

        /**
         * Starts unless already promoted, even with a start pending: a new start withdraws a stop
         * deferred behind that pending one, which would otherwise end coverage on arrival.
         */
        suspend fun ensureStarted(context: Context): Boolean {
            val host = context.applicationContext as? TurnWorkHost
            val current = promotion.value
            if (current != WorkCoverage.LONG_RUNNING && current != WorkCoverage.SHORT_SERVICE) start(context)
            val mode = promotion.first { it != null }
            if (mode == WorkCoverage.SHORT_SERVICE) host?.workCoverageLimited(SHORT_LIMIT)
            if (mode == WorkCoverage.NONE && host?.needsWorkCoverage() == true) {
                host.workCoverageLimited(START_DENIED)
                host.interruptWork(START_DENIED)
            }
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
    private val visibleAnswers = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun delivered(
        context: Context,
        answer: ThreadController.BackgroundAnswer,
    ) {
        if (visibleAnswers.remove(answer.threadId, answer.taskId)) {
            context.getSystemService(NotificationManager::class.java).cancel(answer.threadId.hashCode())
        }
    }

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
    ): Notification = running(context, runningText(tasks, coverage))

    /** Title and details; the notification is reposted only when these change. */
    fun runningText(
        tasks: List<TaskSnapshot>,
        coverage: WorkCoverage,
    ): Pair<String, String> {
        tasks.flatMap { it.questions }.filter { it.waiting }.minByOrNull { it.order }?.let { asking ->
            return "EVA needs your answer" to asking.question
        }
        tasks.firstOrNull { it.question != null }?.let { asking ->
            return "EVA needs your answer" to asking.question.orEmpty()
        }
        val top = tasks.firstOrNull { it.looksStuck } ?: tasks.firstOrNull()
        val elapsed = top?.let { ((System.currentTimeMillis() - it.startedAt).coerceAtLeast(0) / 60_000) } ?: 0
        val details =
            listOfNotNull(
                top?.let { "${it.request} · ${elapsed}m elapsed" },
                "Looks stuck".takeIf { tasks.any { it.looksStuck } },
                TurnWorkService.SHORT_LIMIT.takeIf { coverage == WorkCoverage.SHORT_SERVICE },
            ).joinToString(" · ")
        val title =
            when (tasks.size) {
                0 -> "EVA background work ready"
                1 -> "EVA is working on 1 task"
                else -> "EVA is working on ${tasks.size} tasks"
            }
        return title to details
    }

    fun running(
        context: Context,
        text: Pair<String, String>,
    ): Notification {
        val (title, details) = text
        return NotificationCompat
            .Builder(context, WORK_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title)
            .setContentText(details)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open(context, null))
            // Android holds a deferred notification back about ten seconds, so sub-second work never flashes it.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .addAction(
                0,
                "Stop background work",
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
        visibleAnswers[answer.threadId] = answer.taskId
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
