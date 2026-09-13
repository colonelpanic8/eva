package com.colonelpanic.eva.adapters.android

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

fun interface SmsSender {
    suspend fun send(
        recipient: String,
        message: String,
    ): SmsSendReport
}

/**
 * Sends an SMS without opening another app, so a spoken request can complete hands free.
 * The outcome reports what the platform confirmed, not merely that a send was attempted.
 */
class SmsSendBackend(
    context: Context,
    private val host: AndroidIntentHost,
    private val sender: SmsSender = platformSender(context),
) : ExecutionBackend {
    private val app = context.applicationContext

    override suspend fun unavailableReason(): String? =
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> SmsSendResults.UNSUPPORTED_ANDROID
            !app.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) -> SmsSendResults.NO_TELEPHONY
            else -> host.unavailableReason()
        }

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
        val recipient = arguments.getValue("recipient").trim()
        val message = arguments.getValue("message")
        if (!host.ensurePermission(Manifest.permission.SEND_SMS)) {
            return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, SmsSendResults.PERMISSION_DENIED)
        }
        return SmsSendResults.describe(recipient, message, sender.send(recipient, message))
    }

    companion object {
        const val CONFIRMATION_TIMEOUT_MILLIS = 30_000L

        private fun platformSender(context: Context): SmsSender =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PlatformSmsSender(context)
            } else {
                SmsSender { _, _ -> SmsSendReport(0, failureCode = SmsSendResults.NO_SMS_SERVICE) }
            }
    }
}

/** Waits for the platform's per-part sent broadcasts so a reported send is one the radio accepted. */
@RequiresApi(Build.VERSION_CODES.S)
private class PlatformSmsSender(
    context: Context,
) : SmsSender {
    private val app = context.applicationContext

    override suspend fun send(
        recipient: String,
        message: String,
    ): SmsSendReport =
        withContext(Dispatchers.Main.immediate) {
            val manager =
                app.getSystemService(SmsManager::class.java)
                    ?: return@withContext SmsSendReport(0, failureCode = SmsSendResults.NO_SMS_SERVICE)
            val parts = manager.divideMessage(message).orEmpty().ifEmpty { arrayListOf(message) }
            val action = "${app.packageName}.SMS_SENT.${UUID.randomUUID()}"
            withTimeoutOrNull(SmsSendBackend.CONFIRMATION_TIMEOUT_MILLIS) {
                awaitSend(manager, recipient, parts, action)
            } ?: SmsSendReport(parts.size, timedOut = true)
        }

    private suspend fun awaitSend(
        manager: SmsManager,
        recipient: String,
        parts: List<String>,
        action: String,
    ): SmsSendReport =
        suspendCancellableCoroutine { continuation ->
            var confirmed = 0
            val done = AtomicBoolean(false)
            lateinit var receiver: BroadcastReceiver

            fun finish(report: SmsSendReport) {
                if (!done.compareAndSet(false, true)) return
                runCatching { app.unregisterReceiver(receiver) }
                continuation.resume(report)
            }
            receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context?,
                        intent: Intent?,
                    ) {
                        if (resultCode != Activity.RESULT_OK) {
                            finish(SmsSendReport(parts.size, failureCode = resultCode))
                            return
                        }
                        confirmed += 1
                        if (confirmed == parts.size) finish(SmsSendReport(parts.size))
                    }
                }
            ContextCompat.registerReceiver(app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            continuation.invokeOnCancellation {
                if (done.compareAndSet(false, true)) runCatching { app.unregisterReceiver(receiver) }
            }
            val sentIntents =
                parts.indices.mapTo(ArrayList()) { index ->
                    PendingIntent.getBroadcast(
                        app,
                        index,
                        Intent(action).setPackage(app.packageName),
                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                    )
                }
            try {
                manager.sendMultipartTextMessage(recipient, null, ArrayList(parts), sentIntents, null)
            } catch (_: IllegalArgumentException) {
                finish(SmsSendReport(parts.size, failureCode = SmsSendResults.NO_SMS_SERVICE))
            }
        }
}
