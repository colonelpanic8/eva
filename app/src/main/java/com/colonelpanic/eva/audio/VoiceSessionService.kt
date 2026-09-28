package com.colonelpanic.eva.audio

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
import com.colonelpanic.eva.adapters.android.CurrentLocationBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Holds a foreground notification for the duration of a voice session. Without it Android
 * silences the microphone and may kill playback as soon as a phone action brings another
 * app to the front, which is exactly when the spoken confirmation is due. The notification
 * also carries the mute and end controls, so a backgrounded call stays reachable.
 */
class VoiceSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val host get() = application as? VoiceSessionHost
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val host = host ?: return
        scope.launch {
            host.voiceSession.collect { status ->
                if (foreground) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
                }
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Action intents only reach a service that is already in the foreground, so they must
        // not re-enter startForeground while the session is being torn down.
        when (intent?.action) {
            ACTION_TOGGLE_MICROPHONE -> {
                host?.toggleVoiceMicrophone()
            }

            ACTION_END -> {
                host?.endVoiceSession()
            }

            else -> {
                try {
                    startInForeground()
                } catch (_: SecurityException) {
                    foregroundRejected()
                    return START_NOT_STICKY
                } catch (_: IllegalStateException) {
                    foregroundRejected()
                    return START_NOT_STICKY
                }
                if (gate.foregrounded()) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun foregroundRejected() {
        gate.startRejected()
        host?.voiceUnavailable(START_DENIED)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        gate.destroyed()
        foreground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startInForeground() {
        val notification = notification(host?.voiceSession?.value ?: VoiceSessionStatus())
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                // Both types, so capture and playout keep running once another app takes the screen.
                // The microphone type is only legal with the grant, which a session already required.
                val type =
                    if (MicrophonePermission.isGranted(this)) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    }
                // The location type lets the current-location tool answer while another app has the
                // screen. It is optional: a refusal must not cost the session its audio.
                val located =
                    CurrentLocationBackend.isGranted(this) &&
                        try {
                            startForeground(NOTIFICATION_ID, notification, type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                            true
                        } catch (_: SecurityException) {
                            false
                        }
                if (!located) startForeground(NOTIFICATION_ID, notification, type)
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            }

            else -> {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        foreground = true
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Voice session", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while EVA is in a voice conversation"
            },
        )
    }

    private fun action(
        title: String,
        action: String,
        requestCode: Int,
    ) = NotificationCompat.Action
        .Builder(
            0,
            title,
            PendingIntent.getService(
                this,
                requestCode,
                Intent(this, VoiceSessionService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()

    private fun notification(status: VoiceSessionStatus): Notification {
        val content = voiceNotificationContent(status)
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat
            .Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("EVA is listening")
            .setContentText(content.text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                addAction(action(content.microphoneAction, ACTION_TOGGLE_MICROPHONE, 1))
                addAction(action("End", ACTION_END, 2))
            }.build()
    }

    companion object {
        private const val START_DENIED = "Android could not keep voice active. Invoke EVA through the system assistant and try again."
        private const val CHANNEL = "eva.voice"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_TOGGLE_MICROPHONE = "com.colonelpanic.eva.audio.TOGGLE_MICROPHONE"
        private const val ACTION_END = "com.colonelpanic.eva.audio.END"

        private val gate = ForegroundServiceGate()

        fun start(context: Context) {
            if (!gate.requestStart { ContextCompat.startForegroundService(context, Intent(context, VoiceSessionService::class.java)) }) {
                (context.applicationContext as? VoiceSessionHost)?.voiceUnavailable(START_DENIED)
            }
        }

        fun stop(context: Context) {
            if (gate.stopping()) context.stopService(Intent(context, VoiceSessionService::class.java))
        }
    }
}
