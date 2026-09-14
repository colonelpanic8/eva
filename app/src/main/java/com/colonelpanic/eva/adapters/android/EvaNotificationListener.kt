package com.colonelpanic.eva.adapters.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * The component that carries EVA's notification access. Android gives an unprivileged app no
 * narrower grant for reading the phone's media sessions: `MediaSessionManager.getActiveSessions`
 * accepts either the privileged `MEDIA_CONTENT_CONTROL` permission or an enabled notification
 * listener belonging to the caller. EVA declares this one for the second path.
 *
 * It overrides nothing. Notifications are delivered to it while the grant is on and are ignored;
 * the component exists so the grant can exist, not to listen.
 */
class EvaNotificationListener : NotificationListenerService()

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
