package com.colonelpanic.eva.wear

import android.app.Application
import android.util.Log
import com.colonelpanic.eva.diagnostics.EvaTrace
import com.colonelpanic.eva.diagnostics.TraceLog
import com.colonelpanic.eva.diagnostics.TraceSink
import java.io.File
import java.util.concurrent.Executors

class WatchApplication : Application() {
    val runtime by lazy { WatchRuntime(this) }

    override fun onCreate() {
        super.onCreate()
        val log = TraceLog(directory = File(filesDir, "diagnostics"), writer = Executors.newSingleThreadExecutor())
        EvaTrace.sink =
            TraceSink { event ->
                log.record(event)
                Log.i("EvaTrace", event.line())
            }
        runtime
    }
}
