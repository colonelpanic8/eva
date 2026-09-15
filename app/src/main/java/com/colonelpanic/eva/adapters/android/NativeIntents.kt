package com.colonelpanic.eva.adapters.android

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.net.toUri
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus

/**
 * Executes a capability by handing an implicit intent to Android. The builder may
 * return null when the request cannot be expressed as an intent on this device.
 */
class IntentBackend(
    private val host: AndroidIntentHost,
    private val successMessage: String,
    private val missingAppMessage: String,
    private val build: (Map<String, String>) -> Intent?,
) : ExecutionBackend {
    override suspend fun unavailableReason(): String? = host.unavailableReason()

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
        val intent =
            build(arguments)
                ?: return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, missingAppMessage)
        return host.launch(intent, successMessage, missingAppMessage)
    }
}

object NativeIntents {
    fun alarm(arguments: Map<String, String>): Intent =
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, arguments.getValue("hour").toInt())
            .putExtra(AlarmClock.EXTRA_MINUTES, arguments.getValue("minute").toInt())
            .apply { arguments["label"]?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }

    fun timer(arguments: Map<String, String>): Intent =
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, arguments.getValue("seconds").toInt())
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .apply { arguments["label"]?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }

    fun dial(arguments: Map<String, String>): Intent = Intent(Intent.ACTION_DIAL, "tel:${Uri.encode(arguments.getValue("number"))}".toUri())

    fun webSearch(arguments: Map<String, String>): Intent =
        Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, arguments.getValue("query"))

    fun openUrl(arguments: Map<String, String>): Intent = Intent(Intent.ACTION_VIEW, arguments.getValue("url").toUri())

    fun email(arguments: Map<String, String>): Intent =
        Intent(Intent.ACTION_SENDTO, "mailto:${Uri.encode(arguments.getValue("recipient"))}".toUri())
            .apply {
                arguments["subject"]?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                arguments["body"]?.let { putExtra(Intent.EXTRA_TEXT, it) }
            }

    fun calendarEvent(arguments: Map<String, String>): Intent =
        Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, arguments.getValue("title"))
            .apply {
                arguments["location"]?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                arguments["description"]?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
                val start = arguments["startEpochMillis"]?.toLongOrNull()
                if (start != null) {
                    val minutes = arguments["durationMinutes"]?.toLongOrNull() ?: 60L
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + minutes * 60_000L)
                }
            }

    private val settingsScreens =
        mapOf(
            "wifi" to Settings.ACTION_WIFI_SETTINGS,
            "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "display" to Settings.ACTION_DISPLAY_SETTINGS,
            "sound" to Settings.ACTION_SOUND_SETTINGS,
            "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
            "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "apps" to Settings.ACTION_APPLICATION_SETTINGS,
            "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            "date" to Settings.ACTION_DATE_SETTINGS,
            "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            "all" to Settings.ACTION_SETTINGS,
        )

    val settingsScreenNames: List<String> get() = settingsScreens.keys.sorted()

    fun settings(arguments: Map<String, String>): Intent? = settingsScreens[arguments.getValue("screen")]?.let(::Intent)

    /**
     * Android's documented "play this" request. It carries words, not a chosen track: the music
     * app decides what they match, so what starts is that app's interpretation. Naming an app
     * pins the request to it instead of letting Android offer the choice.
     */
    fun playMedia(arguments: Map<String, String>): Intent = playFromSearch(arguments.getValue("query"))

    /** The same request pinned to one app, for that app's own play action. */
    fun playMediaIn(
        packageName: String,
        query: String,
    ): Intent = playFromSearch(query).setPackage(packageName)

    private fun playFromSearch(query: String): Intent =
        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, UNSTRUCTURED_MEDIA_SEARCH)

    /** Resolves a launcher activity whose visible label best matches the requested app name. */
    fun launchApp(
        context: Context,
        arguments: Map<String, String>,
    ): Intent? {
        val manager = context.packageManager
        val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = bestMatch(manager, manager.queryIntentActivities(launchable, 0), arguments.getValue("app")) ?: return null
        return manager.getLaunchIntentForPackage(match.activityInfo.packageName)
    }

    /** The visible label first, because a spoken app name is a label and not an application ID. */
    private fun bestMatch(
        manager: PackageManager,
        candidates: List<ResolveInfo>,
        app: String,
    ): ResolveInfo? {
        val requested = app.trim().lowercase()
        if (requested.isEmpty()) return null
        return candidates.firstOrNull { it.loadLabel(manager).toString().equals(requested, ignoreCase = true) }
            ?: candidates.firstOrNull {
                it
                    .loadLabel(manager)
                    .toString()
                    .lowercase()
                    .contains(requested)
            }
            ?: candidates.firstOrNull {
                it.activityInfo.packageName
                    .lowercase()
                    .contains(requested)
            }
    }

    /** What the platform calls a search the app has to interpret, rather than a named artist or album. */
    private const val UNSTRUCTURED_MEDIA_SEARCH = "vnd.android.cursor.item/*"
}
