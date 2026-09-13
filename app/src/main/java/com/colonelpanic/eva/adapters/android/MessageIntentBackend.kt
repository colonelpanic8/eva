package com.colonelpanic.eva.adapters.android

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.colonelpanic.eva.capability.ExecutionBackend

class MessageIntentBackend(
    private val host: AndroidIntentHost,
) : ExecutionBackend {
    override suspend fun unavailableReason(): String? = host.unavailableReason()

    override suspend fun execute(arguments: Map<String, String>) =
        host.launch(
            messageIntent(arguments.getValue("recipient"), arguments.getValue("message")),
            "Message draft opened. Send it from your messaging app.",
            "No messaging app is available. Install one and try again.",
        )

    companion object {
        fun messageIntent(
            recipient: String,
            message: String,
        ): Intent = Intent(Intent.ACTION_SENDTO, "smsto:${Uri.encode(recipient)}".toUri()).putExtra("sms_body", message)
    }
}
