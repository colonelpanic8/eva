package com.colonelpanic.eva.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.colonelpanic.eva.EvaApplication
import com.colonelpanic.eva.data.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** Exposes a private trace file to the share sheet; nothing else lives under its paths. */
class DiagnosticsFileProvider : FileProvider()

/**
 * Installs EVA's lifecycle trace (Logcat tag [TAG] plus a persisted ring in private storage) and
 * writes thread and log exports for Android's share sheet. Exports live in the cache directory,
 * are replaced by the next one, and are readable only through a granted content URI.
 */
class AndroidDiagnostics(
    private val app: EvaApplication,
) {
    val trace by lazy { TraceLog(TraceLog.DEFAULT_CAPACITY, File(app.filesDir, "diagnostics"), Executors.newSingleThreadExecutor()) }

    fun install(
        scope: CoroutineScope,
        verbose: StateFlow<Boolean>,
    ) {
        val log = trace
        EvaTrace.sink =
            TraceSink { event ->
                log.record(event)
                Log.i(TAG, event.line())
            }
        scope.launch { verbose.collect { EvaTrace.verbose = it } }
        EvaTrace.info("app.started", "version" to app.clientVersion, "code" to app.versionCode, "build" to buildType())
    }

    suspend fun threadExport(threadId: String): Export {
        val snapshot =
            DiagnosticsExport.collect(
                app.conversations,
                app.invocations,
                threadId,
                environment(),
                app.controller.taskSnapshots.value,
                trace.snapshot(),
                System.currentTimeMillis(),
                providerEvents = ProviderEventLog.realtime.snapshot(),
            )
        return write("eva-thread-${threadId.take(8)}", DiagnosticsExport.assemble(snapshot, redactor()))
    }

    suspend fun logsExport(): Export =
        write(
            "eva-logs",
            DiagnosticsExport.logs(
                environment(),
                trace.snapshot(),
                System.currentTimeMillis(),
                redactor(),
                ProviderEventLog.realtime.snapshot(),
            ),
        )

    fun share(
        context: Context,
        export: Export,
    ) {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.diagnostics", export.file)
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, export.file.name)
                putExtra(Intent.EXTRA_TEXT, export.summary)
                clipData = ClipData.newRawUri(export.file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(
            Intent.createChooser(send, "Share EVA diagnostics").apply {
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    data class Export(
        val file: File,
        val summary: String,
    )

    private suspend fun write(
        name: String,
        document: kotlinx.serialization.json.JsonObject,
    ): Export =
        withContext(Dispatchers.IO) {
            val directory = File(app.cacheDir, "diagnostics").apply { mkdirs() }
            directory.listFiles()?.forEach { it.delete() }
            val file = File(directory, "$name-${System.currentTimeMillis()}.json")
            file.writeText(DiagnosticsExport.render(document))
            EvaTrace.info("diagnostics.exported", "kind" to name.substringBefore('-', name), "bytes" to file.length())
            Export(file, DiagnosticsExport.summaryText(document))
        }

    /** Every value in the device's secret store is redacted wherever it appears, on top of the pattern rules. */
    private suspend fun redactor() = withContext(Dispatchers.IO) { Redactor(SecretStore(app).values()) }

    private fun buildType() = if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) "debug" else "release"

    private fun environment(): Map<String, String> =
        linkedMapOf(
            "appVersion" to (app.clientVersion ?: "unknown"),
            "versionCode" to (app.versionCode?.toString() ?: "unknown"),
            "buildType" to buildType(),
            "applicationId" to app.packageName,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "os" to "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "access" to
                when {
                    app.chatGpt.signedIn -> "chatgpt-subscription"
                    app.settings.apiKey() != null -> "api-key"
                    else -> "none"
                },
            "textModel" to app.settings.textModel,
            "realtimeModel" to app.settings.realtimeModel,
            "verboseLogging" to EvaTrace.verbose.toString(),
        )

    companion object {
        const val TAG = "EvaTrace"
    }
}
