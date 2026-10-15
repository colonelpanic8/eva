package com.colonelpanic.eva.wear

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.colonelpanic.eva.diagnostics.EvaTrace
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

class WatchRuntime(
    private val context: Context,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val tasks = WatchTaskClient(context, scope, ::phoneNode)
    private val prefs = context.getSharedPreferences("eva.watch", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(WatchUi())
    val state = mutable.asStateFlow()
    var config = WearGestureConfiguration()
        private set
    var paused = prefs.getBoolean("paused", false)
        private set
    private var phone: String? = prefs.getString("phone", null)
    private val nearMisses = ArrayDeque<Long>()
    private var traceSequence = prefs.getLong("traceSequence", 0)
    private var configRevision = prefs.getLong("configRevision", 0)
    private var pendingSettings: WatchSettingsRequest? = null
    private val results = linkedMapOf<String, String>()
    private val pending = mutableMapOf<String, String>()

    init {
        try {
            config = prefs.getString("config", null)?.let { WearProtocol.decode<WatchConfiguration>(it.toByteArray()).value } ?: config
            mutable.value = state.value.copy(enabled = config.enabled && !paused, requiredPulses = config.requiredPulses)
        } catch (error: Exception) {
            error("Saved watch configuration is invalid. Sync phone settings to repair it.")
        }
    }

    fun status(text: String) {
        mutable.value = state.value.copy(status = text)
    }

    fun error(text: String) {
        status(text)
        EvaTrace.info("wear.watch.failed", "reason" to text)
        vibrate(true)
    }

    fun vibrate(failed: Boolean = false) {
        val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
        if (failed) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 80, 100, 80, 100), -1))
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    @SuppressLint("UseKtx")
    fun apply(
        bytes: ByteArray,
        node: String,
    ) {
        try {
            val payload = WearProtocol.decode<WatchConfiguration>(bytes)
            if (payload.revision < configRevision && node == phone) return
            val value = payload.value
            check(
                prefs
                    .edit()
                    .putString("config", bytes.toString(Charsets.UTF_8))
                    .putString("phone", node)
                    .putLong("configRevision", payload.revision)
                    .commit(),
            ) {
                "Could not cache phone settings."
            }
            config = value
            configRevision = payload.revision
            phone = node
            mutable.value = state.value.copy(enabled = value.enabled && !paused, requiredPulses = value.requiredPulses)
            reconcile()
        } catch (
            error: Exception,
        ) {
            error("Could not apply phone settings (${error.javaClass.simpleName}). Open EVA on the phone and sync again.")
        }
    }

    fun reconcile() {
        if (!config.enabled || paused) {
            context.stopService(Intent(context, GestureService::class.java))
            status(if (paused) "Paused on this watch. Enable to resume." else "Disabled by phone settings.")
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, GestureService::class.java))
        } catch (error: Exception) {
            error("Android refused background listening (${error.javaClass.simpleName}). Open EVA on this watch and enable it again.")
        }
    }

    @SuppressLint("UseKtx")
    fun pause() {
        check(prefs.edit().putBoolean("paused", true).commit()) { "Could not pause watch sensing." }
        paused = true
        mutable.value = state.value.copy(enabled = false)
        reconcile()
    }

    fun toggle(enabled: Boolean) {
        if (!enabled) pause()
        val request = WatchSettingsRequest(UUID.randomUUID().toString(), enabled)
        pendingSettings = request
        scope.launch {
            try {
                val node = phoneNode()
                Wearable.getMessageClient(context).sendMessage(node, WearProtocol.ENABLE, WearProtocol.encode(request)).await()
                status("Setting requested on phone; waiting for confirmation.")
                delay(30_000)
                if (pendingSettings?.id == request.id) {
                    pendingSettings = null
                    error("The phone did not confirm the setting. Keep the watch paused and sync again when connected.")
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (pendingSettings?.id == request.id) pendingSettings = null
                error("Could not change phone settings. ${error.message ?: "Reconnect the paired phone and sync."}")
            }
        }
    }

    @SuppressLint("UseKtx")
    private fun configured(
        node: String,
        value: WatchConfiguration,
        resume: Boolean,
    ) {
        if (resume) {
            check(prefs.edit().putBoolean("paused", false).commit()) { "Could not resume watch sensing." }
            paused = false
        }
        apply(WearProtocol.encode(value), node)
    }

    fun refresh() {
        scope.launch {
            try {
                val node = phoneNode()
                Wearable.getMessageClient(context).sendMessage(node, WearProtocol.REFRESH, byteArrayOf()).await()
                readConfiguration()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                error("Could not sync. ${error.message ?: "Reconnect the paired phone and open EVA there."}")
            }
        }
    }

    private suspend fun readConfiguration() {
        val items = Wearable.getDataClient(context).dataItems.await()
        try {
            for (item in items) {
                if (item.uri.path == WearProtocol.CONFIG &&
                    item.uri.host == phone
                ) {
                    apply(checkNotNull(item.data), checkNotNull(item.uri.host))
                }
            }
        } finally {
            items.release()
        }
    }

    private suspend fun phoneNode(): String {
        val nodes =
            Wearable
                .getCapabilityClient(
                    context,
                ).getCapability(WearProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
        val node =
            nodes.firstOrNull { it.id == phone }?.id ?: nodes.singleOrNull()?.id
                ?: throw IllegalStateException("Connect exactly one paired phone with the matching EVA app and signer installed.")
        phone = node
        return node
    }

    fun gesture(event: WatchGestureEvent) {
        val now = System.currentTimeMillis()
        val record = WatchTraceRecord(UUID.randomUUID().toString(), now, event)
        traceGesture(record)
        if (event.detected) {
            vibrate()
            mutable.value =
                state.value.copy(
                    lastDetection =
                        java.text.DateFormat
                            .getTimeInstance()
                            .format(java.util.Date(now)),
                )
            trigger(record.id, now)
        } else {
            nearMisses.addLast(now)
        }
        recentNearMisses(now)
        scope.launch {
            try {
                // Data Layer is the offline outbox; the phone imports these into its existing trace/export.
                val slot = traceSequence++
                prefs.edit { putLong("traceSequence", traceSequence) }
                Wearable
                    .getDataClient(
                        context,
                    ).putDataItem(PutDataRequest.create(WearProtocol.TRACE + slot % 2000).setData(WearProtocol.encode(record)).setUrgent())
                    .await()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                error("Gesture diagnostics could not sync (${error.javaClass.simpleName}). Reconnect Google Play services.")
            }
        }
    }

    fun recentNearMisses(now: Long = System.currentTimeMillis()): Int {
        while (nearMisses.firstOrNull()?.let { now - it > 300_000 } == true) nearMisses.removeFirst()
        mutable.value = state.value.copy(nearMisses = nearMisses.size)
        return nearMisses.size
    }

    private fun trigger(
        id: String,
        now: Long,
    ) {
        status("Gesture detected; requesting EVA voice…")
        scope.launch {
            try {
                val node = phoneNode()
                pending[id] = node
                Wearable
                    .getMessageClient(
                        context,
                    ).sendMessage(node, WearProtocol.START, WearProtocol.encode(WatchStartRequest(id, now)))
                    .await()
                delay(30_000)
                if (pending.remove(id) !=
                    null
                ) {
                    error(
                        "No voice-start confirmation from the phone. Outcome unknown; no retry was sent. Open EVA on the phone to check.",
                    )
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                pending.remove(id)
                error("Voice trigger delivery failed; outcome unknown. ${error.message ?: "Check the paired phone."}")
            }
        }
    }

    fun result(
        node: String,
        result: WatchStartResult,
    ) {
        if (node != phone && pending[result.id] != node) return
        if (results[result.id] == "connected" || (result.status == "requested" && results[result.id] != null)) return
        if (result.status != "requested") {
            results[result.id] = result.status
            if (results.size > 128) results.remove(results.keys.first())
        }
        if (result.status != "requested") pending.remove(result.id)
        if (result.status in setOf("failed", "unknown")) error(result.message) else status(result.message)
        if (result.status == "configured") {
            val request = pendingSettings?.takeIf { it.id == result.id } ?: return
            pendingSettings = null
            result.configuration?.let { configured(node, it, request.enabled) }
        }
    }
}

data class WatchUi(
    val enabled: Boolean = false,
    val requiredPulses: Int = 4,
    val status: String = "Open EVA on the phone, then sync settings.",
    val lastDetection: String = "—",
    val nearMisses: Int = 0,
)
