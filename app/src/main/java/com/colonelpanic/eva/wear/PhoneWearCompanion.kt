package com.colonelpanic.eva.wear

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.colonelpanic.eva.EvaApplication
import com.colonelpanic.eva.MainActivity
import com.colonelpanic.eva.R
import com.colonelpanic.eva.assist.AssistantRole
import com.colonelpanic.eva.assist.EvaVoiceInteractionService
import com.colonelpanic.eva.audio.MicrophonePermission
import com.colonelpanic.eva.conversation.ProviderStatus
import com.colonelpanic.eva.diagnostics.EvaTrace
import com.colonelpanic.eva.diagnostics.TraceEvent
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs

/** Phone-owned configuration and at-most-once assistant admission. Never retries a voice start. */
class PhoneWearCompanion(
    private val app: EvaApplication,
    private val scope: CoroutineScope,
    private val assistantSelected: () -> Boolean = { AssistantRole.isEva(app) },
    private val microphoneGranted: () -> Boolean = { MicrophonePermission.isGranted(app) },
    private val showSession: (String) -> Boolean = { EvaVoiceInteractionService.showAssistant(watchTriggerId = it) },
    private val deliver: suspend (String, WatchStartResult) -> Unit = { node, result ->
        Wearable.getMessageClient(app).sendMessage(node, WearProtocol.RESULT, WearProtocol.encode(result)).await()
    },
) {
    private val prefs = app.getSharedPreferences("eva.wear.requests", Context.MODE_PRIVATE)
    private var observing = false

    fun observeConfiguration() {
        if (observing) return
        observing = true
        prefs.getString("pendingId", null)?.let {
            finish(it, "unknown", "EVA restarted during voice startup. The previous outcome is unknown; no trigger was retried.")
        }
        scope.launch {
            app.configuration.awaitReady()
            app.wearGestureSettings.state.collect { sync() }
        }
    }

    @SuppressLint("UseKtx")
    suspend fun sync(): WatchConfiguration? {
        try {
            val revision = maxOf(System.currentTimeMillis(), prefs.getLong("configRevision", 0) + 1)
            check(prefs.edit().putLong("configRevision", revision).commit()) { "Could not save watch configuration revision." }
            val payload = WatchConfiguration(revision, app.wearGestureSettings.value)
            Wearable
                .getDataClient(app)
                .putDataItem(
                    PutDataRequest.create(WearProtocol.CONFIG).setData(WearProtocol.encode(payload)).setUrgent(),
                ).await()
            EvaTrace.info("wear.config.synced", "enabled" to payload.value.enabled)
            return payload
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            EvaTrace.info("wear.config.failed", "error" to error.javaClass.simpleName)
            if (app.wearGestureSettings.value.enabled) {
                notifyFailure(
                    "Watch configuration could not sync. Check Google Play services and the watch connection, then use Sync watch in EVA settings.",
                )
            }
            return null
        }
    }

    suspend fun message(
        node: String,
        path: String,
        data: ByteArray,
    ) = withContext(Dispatchers.Main.immediate) {
        try {
            app.configuration.awaitReady()
            when (path) {
                WearProtocol.REFRESH -> {
                    sync()
                }

                WearProtocol.ENABLE -> {
                    val request = WearProtocol.decode<WatchSettingsRequest>(data)
                    app.wearGestureSettings.save(app.wearGestureSettings.value.copy(enabled = request.enabled))
                    val payload = sync()
                    send(
                        node,
                        if (payload == null) {
                            WatchStartResult(
                                request.id,
                                "failed",
                                "Phone settings saved, but watch sync failed. Reconnect Google Play services and sync again.",
                            )
                        } else {
                            WatchStartResult(request.id, "configured", "Phone settings applied.", payload)
                        },
                    )
                }

                WearProtocol.START -> {
                    start(node, WearProtocol.decode(data))
                }
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            EvaTrace.info("wear.request.failed", "error" to error.javaClass.simpleName)
            send(
                node,
                WatchStartResult(
                    "configuration",
                    "failed",
                    "EVA could not read or apply the watch request. Open EVA on the phone and check User configuration.",
                ),
            )
        }
    }

    @SuppressLint("UseKtx")
    internal suspend fun start(
        node: String,
        request: WatchStartRequest,
    ) {
        require(UUID.fromString(request.id).toString() == request.id) { "Invalid watch request identity." }
        val prior = prefs.getString(request.id, null)
        if (prior != null) {
            send(node, WearProtocol.decode(prior.toByteArray()))
            return
        }
        val state = app.controller.state.value
        val failure =
            if (prefs.getString("pendingId", null) != null) {
                "A watch-triggered assistant session is still starting. Wait for its outcome before twisting again."
            } else if (abs(System.currentTimeMillis() - request.atMillis) > 60_000) {
                "The watch trigger is stale or the device clocks differ. Check automatic time on both devices and twist again."
            } else {
                watchStartFailure(
                    app.wearGestureSettings.value.enabled,
                    assistantSelected(),
                    microphoneGranted(),
                    state.voiceMode && state.providerStatus != ProviderStatus.DISCONNECTED,
                )
            }
        val initial =
            WatchStartResult(
                request.id,
                if (failure ==
                    null
                ) {
                    "requested"
                } else {
                    "failed"
                },
                failure ?: "Assistant requested; waiting for voice connection.",
            )
        // Reserve before showSession: losing delivery or the process must never repeat a start.
        val editor = prefs.edit().putLong(request.id + ".at", request.atMillis)
        val pendingId = prefs.getString("pendingId", null)
        prefs.all.keys.filter { it.endsWith(".at") }.forEach { key ->
            val id = key.removeSuffix(".at")
            if (id != pendingId && System.currentTimeMillis() - prefs.getLong(key, Long.MAX_VALUE) > 60_000) {
                editor.remove(id).remove(key).remove(id + ".node")
            }
        }
        check(editor.putString(request.id, WearProtocol.encode(initial).toString(Charsets.UTF_8)).commit()) {
            "Could not reserve the watch request."
        }
        if (failure != null) {
            notifyFailure(failure)
            send(node, initial)
            return
        }
        check(
            prefs
                .edit()
                .putString(request.id + ".node", node)
                .putString("pendingId", request.id)
                .putString("pendingNode", node)
                .commit(),
        ) { "Could not retain watch result delivery." }
        EvaTrace.info("wear.start.requested", "request" to request.id, "node" to node)
        try {
            if (!showSession(request.id)) {
                fail(
                    request.id,
                    "EVA's assistant service is not ready. Open EVA on the phone and select it as the default digital assistant, then try again.",
                )
            } else {
                scope.launch {
                    delay(30_000)
                    if (prefs.getString("pendingId", null) == request.id) {
                        finish(
                            request.id,
                            "unknown",
                            "EVA has not confirmed voice startup. Outcome unknown; no start was retried. Open EVA on the phone to check.",
                        )
                    }
                }
                send(node, initial)
            }
        } catch (error: Exception) {
            fail(
                request.id,
                "Android refused EVA's assistant session (${error.javaClass.simpleName}). Open EVA on the phone and check its default assistant and microphone settings.",
            )
        }
    }

    fun onTrace(event: TraceEvent) {
        val id = event.fields["request"] ?: return
        when (event.name) {
            "wear.session.connected" -> {
                finish(id, "connected", "EVA voice connected.")
            }

            "wear.session.closed" -> {
                if (event.fields["connected"] ==
                    "false"
                ) {
                    fail(id, event.fields["reason"] ?: "EVA voice could not connect. Open EVA on the phone for details.")
                }
            }
        }
    }

    fun fail(
        id: String,
        reason: String,
    ) = finish(id, "failed", reason)

    @SuppressLint("UseKtx")
    private fun finish(
        id: String,
        status: String,
        message: String,
    ) {
        val node = prefs.getString(id + ".node", null) ?: return
        val existing = prefs.getString(id, null)?.let { WearProtocol.decode<WatchStartResult>(it.toByteArray()) } ?: return
        if (existing.status == status || existing.status == "connected") return
        val result = WatchStartResult(id, status, message)
        val editor = prefs.edit().putString(id, WearProtocol.encode(result).toString(Charsets.UTF_8))
        if (prefs.getString("pendingId", null) == id) editor.remove("pendingId").remove("pendingNode")
        check(editor.commit()) { "Could not save watch session outcome." }
        EvaTrace.info("wear.start.result", "request" to id, "status" to status, "reason" to message)
        if (status == "failed") notifyFailure(message)
        scope.launch { send(node, result) }
    }

    private suspend fun send(
        node: String,
        result: WatchStartResult,
    ) {
        try {
            deliver(node, result)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            EvaTrace.info(
                "wear.result.undelivered",
                "request" to result.id,
                "status" to result.status,
                "error" to error.javaClass.simpleName,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyFailure(message: String) {
        EvaTrace.info("wear.start.failed", "reason" to message)
        val notifications = app.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, "Watch gesture errors", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val pending =
            PendingIntent.getActivity(
                app,
                0,
                Intent(app, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        notifications.notify(
            7201,
            NotificationCompat
                .Builder(
                    app,
                    CHANNEL,
                ).setSmallIcon(
                    R.drawable.ic_mic,
                ).setContentTitle(
                    "EVA watch gesture needs attention",
                ).setContentText(
                    message,
                ).setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val CHANNEL = "eva.watch.errors"
    }
}
