package com.colonelpanic.eva.adapters.android

import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.colonelpanic.eva.EvaApplication
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.messaging.MessagingApp
import java.lang.ref.WeakReference
import java.security.MessageDigest

/** Reads only messaging notifications after a separate in-app opt-in. */
class EvaNotificationListener : NotificationListenerService() {
    private val eva get() = application as EvaApplication

    override fun onListenerConnected() {
        current = WeakReference(this)
        refresh()
    }

    override fun onListenerDisconnected() {
        eva.notificationMessages.clear()
        if (current?.get() === this) current = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        capture(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        eva.notificationMessages.remove(sbn.key)
    }

    private fun refresh() {
        eva.notificationMessages.clear()
        if (!eva.messagingSettings.state.value.enabled) return
        runCatching { activeNotifications?.takeLast(100)?.forEach(::capture) }
    }

    private fun capture(sbn: StatusBarNotification) {
        eva.notificationMessages.remove(sbn.key)
        if (!eva.messagingSettings.state.value.enabled || sbn.user != android.os.Process.myUserHandle()) return
        val notification = sbn.notification
        if (notification.category != Notification.CATEGORY_MESSAGE ||
            notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        ) {
            return
        }
        runCatching {
            val app = messagingApp(sbn.packageName)
            val title =
                notification.extras.getCharSequence(androidx.core.app.NotificationCompat.EXTRA_CONVERSATION_TITLE)
                    ?: notification.extras.getCharSequence(Notification.EXTRA_TITLE) ?: return
            val style =
                androidx.core.app.NotificationCompat.MessagingStyle
                    .extractMessagingStyleFromNotification(notification)
            val text =
                style?.messages?.takeLast(5)?.joinToString("\n") {
                    (
                        it.person
                            ?.name
                            ?.toString()
                            ?.take(100) ?: ""
                    ) + ": " + it.text.toString().take(400)
                } ?: notification.extras
                    .getCharSequence(Notification.EXTRA_TEXT)
                    ?.toString()
                    ?.take(1600)
                    .orEmpty()
            val action = NotificationReply.action(notification, sbn.packageName, packageManager.getApplicationInfo(sbn.packageName, 0).uid)
            eva.notificationMessages.publish(
                sbn.key,
                app,
                title.toString(),
                text,
                action?.let { selected ->
                    { message ->
                        val active = activeNotifications?.firstOrNull { it.key == sbn.key }
                        if (!eva.messagingSettings.state.value.enabled ||
                            app.identity !in eva.messagingSettings.state.value.replies ||
                            !MediaControlAccess.isGranted(this) ||
                            getSystemService(KeyguardManager::class.java).isDeviceLocked ||
                            active == null || active.postTime != sbn.postTime ||
                            messagingApp(sbn.packageName).identity != app.identity ||
                            active.notification.actions
                                .orEmpty()
                                .none { it.actionIntent == selected.actionIntent }
                        ) {
                            ExecutionOutcome(
                                InvocationStatus.NOT_EXECUTED,
                                "The notification changed. Search again; nothing was sent.",
                            )
                        } else {
                            val intent = NotificationReply.intent(selected, message)
                            try {
                                selected.actionIntent.send(this, 0, intent)
                                ExecutionOutcome(
                                    InvocationStatus.HANDED_OFF,
                                    "Reply handed to " + app.title + ". Delivery is not confirmed; check the app before retrying.",
                                )
                            } catch (_: PendingIntent.CanceledException) {
                                ExecutionOutcome(
                                    InvocationStatus.NOT_EXECUTED,
                                    "The reply action expired. Search again; nothing was sent.",
                                )
                            }
                        }
                    }
                },
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun messagingApp(name: String): MessagingApp {
        val info =
            packageManager.getPackageInfo(
                name,
                if (Build.VERSION.SDK_INT >= 28) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    PackageManager.GET_SIGNATURES
                },
            )
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val signers =
            requireNotNull(signatures)
                .map {
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest(it.toByteArray())
                        .joinToString("") { b -> "%02x".format(b) }
                }.sorted()
                .joinToString(",")
        val application = requireNotNull(info.applicationInfo)
        return MessagingApp(
            application.uid.toString() + ":" + name + ":" + info.firstInstallTime + ":" + signers,
            name,
            application.loadLabel(packageManager).toString().take(80),
        )
    }

    companion object {
        private var current: WeakReference<EvaNotificationListener>? = null

        fun refreshMessages() {
            current?.get()?.refresh()
        }
    }
}

/** Whether EVA can see media sessions, and the screens where that is turned on or off. */
object MediaControlAccess {
    fun isGranted(context: Context): Boolean =
        runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }.getOrDefault(false)

    fun component(context: Context): ComponentName = ComponentName(context, EvaNotificationListener::class.java)

    /** EVA's own row where the device has one, then the listener list, then the settings root. */
    fun settingsIntents(context: Context): List<Intent> =
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString()),
                )
            }
            add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
}
