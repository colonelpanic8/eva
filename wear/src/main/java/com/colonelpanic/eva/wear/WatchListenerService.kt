package com.colonelpanic.eva.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class WatchListenerService : WearableListenerService() {
    private val runtime get() = (application as WatchApplication).runtime

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != WearProtocol.CONFIG) continue
            val data = event.dataItem.data ?: continue
            val node = event.dataItem.uri.host ?: continue
            runBlocking { withContext(Dispatchers.Main.immediate) { runtime.apply(data, node) } }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearProtocol.RESULT) return
        runBlocking {
            withContext(Dispatchers.Main.immediate) {
                try {
                    runtime.result(event.sourceNodeId, WearProtocol.decode(event.data))
                } catch (
                    error: Exception,
                ) {
                    runtime.error("Invalid reply from EVA. Update EVA on both devices and sync settings.")
                }
            }
        }
    }
}

class WatchBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        (context.applicationContext as WatchApplication).runtime.reconcile()
    }
}
