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

/** The application supplies the controller so the service can interrupt work Android will not let it finish. */
interface TurnWorkHost {
    fun needsWorkCoverage(): Boolean

    /** Interrupt only dependent work after Android refuses coverage. */
    fun interruptWork(reason: String)
}

/**
 * Covers text and detached turns, including work delegated during a call. Renews short-service
 * coverage when Android permits it; only a live voice turn has another service of its own.
 */
class TurnWorkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (!promote()) {
            foregroundRejected()
            return START_NOT_STICKY
        }
        if (gate.foregrounded()) stopCoverage()
        return START_NOT_STICKY
    }

    private fun promote(): Boolean {
        WorkNotifications.channels(this)
        val notification =
            NotificationCompat
                .Builder(this, WorkNotifications.WORK_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("EVA is finishing a request")
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setContentIntent(WorkNotifications.open(this, null))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: SecurityException) {
            return false
        } catch (_: IllegalStateException) {
            return false
        }
        return true
    }

    private fun foregroundRejected() {
        gate.startRejected()
        (application as? TurnWorkHost)?.interruptWork(START_DENIED)
        stopSelf()
    }

    override fun onDestroy() {
        gate.destroyed()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int) {
        val host = application as? TurnWorkHost
        if (host?.needsWorkCoverage() == true) {
            if (promote()) return
            host.interruptWork(START_DENIED)
        }
        stopCoverage()
    }

    private fun stopCoverage() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val START_DENIED = "Android did not allow EVA to continue this request in the background."
        private const val NOTIFICATION_ID = 42
        private val gate = ForegroundServiceGate()

        fun start(context: Context) {
            if (!gate.requestStart { ContextCompat.startForegroundService(context, Intent(context, TurnWorkService::class.java)) }) {
                (context.applicationContext as? TurnWorkHost)?.interruptWork(START_DENIED)
            }
        }

        fun stop(context: Context) {
            if (gate.stopping()) context.stopService(Intent(context, TurnWorkService::class.java))
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
    const val EXTRA_THREAD_ID = "com.colonelpanic.eva.THREAD_ID"

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
            Intent(context, MainActivity::class.java).apply { threadId?.let { putExtra(EXTRA_THREAD_ID, it) } },
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
