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
    fun interruptWork(reason: String)
}

/**
 * Keeps the process alive while a turn finishes with nothing attached to its thread: after a
 * call was hung up, or a text connection dropped. Short by Android's definition, which fits;
 * a turn that cannot finish inside the allowance is interrupted rather than left half-done.
 */
class TurnWorkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (gate.foregrounded()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        gate.destroyed()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int) {
        (application as? TurnWorkHost)?.interruptWork("EVA ran out of background time before this request finished.")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private val gate = ForegroundServiceGate()

        fun start(context: Context) {
            gate.starting()
            ContextCompat.startForegroundService(context, Intent(context, TurnWorkService::class.java))
        }

        fun stop(context: Context) {
            if (gate.stopping()) context.stopService(Intent(context, TurnWorkService::class.java))
        }
    }
}

/** Notifications for work that finished with nobody watching. */
object WorkNotifications {
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
                .setContentTitle(answer.title)
                .setContentText(answer.answer.lineSequence().firstOrNull { it.isNotBlank() } ?: "Finished.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(answer.answer))
                .setAutoCancel(true)
                .setContentIntent(open(context, answer.threadId))
                .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(answer.threadId.hashCode(), notification) }
    }
}
