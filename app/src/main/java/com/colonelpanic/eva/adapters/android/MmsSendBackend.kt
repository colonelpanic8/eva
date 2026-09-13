package com.colonelpanic.eva.adapters.android

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** What the platform reported for one group message: an MMS error code, silence, or a refusal to try. */
data class MmsSendReport(
    val errorCode: Int? = null,
    val timedOut: Boolean = false,
    val unsupported: String? = null,
) {
    companion object {
        const val NO_SERVICE = "this device exposes no MMS service"
        const val NO_STORAGE = "the message could not be prepared for sending"
        const val MMS_DISABLED = "this carrier has multimedia messaging switched off"
        const val GROUP_DISABLED = "this carrier has group messaging switched off"
    }
}

fun interface MmsSender {
    suspend fun send(
        recipients: List<String>,
        message: String,
    ): MmsSendReport
}

/**
 * Sends a group text as an MMS through `SmsManager`, so every recipient sees one shared conversation.
 * The PDU is written to a private cache file that the telephony stack reads through a granted content
 * URI; nothing is broadcast or left behind once the send resolves.
 */
@RequiresApi(Build.VERSION_CODES.S)
class PlatformMmsSender(
    context: Context,
) : MmsSender {
    private val app = context.applicationContext

    override suspend fun send(
        recipients: List<String>,
        message: String,
    ): MmsSendReport =
        withContext(Dispatchers.Main.immediate) {
            val manager =
                app.getSystemService(SmsManager::class.java)
                    ?: return@withContext MmsSendReport(unsupported = MmsSendReport.NO_SERVICE)
            carrierRefusal(manager)?.let { return@withContext MmsSendReport(unsupported = it) }
            val transaction = UUID.randomUUID().toString()
            val file =
                runCatching { write(transaction, MmsPdu.sendRequest(recipients, message, transaction)) }
                    .getOrNull() ?: return@withContext MmsSendReport(unsupported = MmsSendReport.NO_STORAGE)
            val report =
                withTimeoutOrNull(CONFIRMATION_TIMEOUT_MILLIS) { awaitSend(manager, file, transaction) }
                    ?: MmsSendReport(timedOut = true)
            // A timed-out send may still be reading the PDU; the next send sweeps whatever it leaves behind.
            if (!report.timedOut) file.delete()
            report
        }

    /** Carriers can switch group messaging off; saying so beats sending separate texts the user did not ask for. */
    private fun carrierRefusal(manager: SmsManager): String? {
        val configuration = runCatching { manager.carrierConfigValues }.getOrNull() ?: return null
        return when {
            !configuration.getBoolean(SmsManager.MMS_CONFIG_MMS_ENABLED, true) -> MmsSendReport.MMS_DISABLED
            !configuration.getBoolean(SmsManager.MMS_CONFIG_GROUP_MMS_ENABLED, true) -> MmsSendReport.GROUP_DISABLED
            else -> null
        }
    }

    private fun write(
        transaction: String,
        pdu: ByteArray,
    ): File {
        val directory = File(app.cacheDir, DIRECTORY).apply { mkdirs() }
        val expiry = System.currentTimeMillis() - ABANDONED_MILLIS
        directory.listFiles()?.forEach { stale -> if (stale.lastModified() < expiry) stale.delete() }
        return File(directory, "$transaction.pdu").apply { writeBytes(pdu) }
    }

    private suspend fun awaitSend(
        manager: SmsManager,
        file: File,
        transaction: String,
    ): MmsSendReport =
        suspendCancellableCoroutine { continuation ->
            val action = "${app.packageName}.MMS_SENT.$transaction"
            val done = AtomicBoolean(false)
            lateinit var receiver: BroadcastReceiver

            fun finish(report: MmsSendReport) {
                if (!done.compareAndSet(false, true)) return
                runCatching { app.unregisterReceiver(receiver) }
                continuation.resume(report)
            }
            receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context?,
                        intent: Intent?,
                    ) = finish(if (resultCode == Activity.RESULT_OK) MmsSendReport() else MmsSendReport(errorCode = resultCode))
                }
            ContextCompat.registerReceiver(app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            continuation.invokeOnCancellation {
                if (done.compareAndSet(false, true)) runCatching { app.unregisterReceiver(receiver) }
            }
            val sent =
                PendingIntent.getBroadcast(
                    app,
                    0,
                    Intent(action).setPackage(app.packageName),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                )
            try {
                val uri = FileProvider.getUriForFile(app, "${app.packageName}$AUTHORITY_SUFFIX", file)
                app.grantUriPermission(TELEPHONY_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                manager.sendMultimediaMessage(app, uri, null, null, sent)
            } catch (_: IllegalArgumentException) {
                finish(MmsSendReport(unsupported = MmsSendReport.NO_SERVICE))
            } catch (_: SecurityException) {
                finish(MmsSendReport(unsupported = MmsSendReport.NO_SERVICE))
            }
        }

    companion object {
        const val CONFIRMATION_TIMEOUT_MILLIS = 60_000L
        const val ABANDONED_MILLIS = 60 * 60 * 1000L
        const val DIRECTORY = "mms"
        const val AUTHORITY_SUFFIX = ".mms"
        const val TELEPHONY_PACKAGE = "com.android.phone"
    }
}
