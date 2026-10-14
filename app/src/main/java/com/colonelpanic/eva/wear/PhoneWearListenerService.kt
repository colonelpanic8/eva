package com.colonelpanic.eva.wear

import com.colonelpanic.eva.EvaApplication
import com.colonelpanic.eva.diagnostics.EvaTrace
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking

class PhoneWearListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        runBlocking { (application as EvaApplication).wearCompanion.message(event.sourceNodeId, event.path, event.data) }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED ||
                !event.dataItem.uri.path
                    .orEmpty()
                    .startsWith(WearProtocol.TRACE)
            ) {
                continue
            }
            try {
                traceGesture(WearProtocol.decode(checkNotNull(event.dataItem.data)), event.dataItem.uri.host)
                Wearable.getDataClient(this).deleteDataItems(event.dataItem.uri).addOnFailureListener {
                    EvaTrace.info("wear.trace.ack_failed", "error" to it.javaClass.simpleName)
                }
            } catch (error: Exception) {
                EvaTrace.info("wear.trace.failed", "error" to error.javaClass.simpleName)
            }
        }
    }
}
