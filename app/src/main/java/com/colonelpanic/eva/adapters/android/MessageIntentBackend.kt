package com.colonelpanic.eva.adapters.android

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome

class MessageIntentBackend(
    private val host: AndroidIntentHost,
    private val targets: MessageTargets,
) : ExecutionBackend {
    override suspend fun unavailableReason(): String? = host.unavailableReason()

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome =
        when (val target = targets.resolve(arguments)) {
            is MessageTargets.Target.Refused -> {
                target.outcome
            }

            is MessageTargets.Target.Recipients -> {
                host.launch(
                    messageIntent(target.numbers, arguments.getValue("message")),
                    if (target.numbers.size > 1) GROUP_OPENED else OPENED,
                    "No messaging app is available. Install one and try again.",
                )
            }
        }

    companion object {
        const val OPENED = "Message draft opened. Send it from your messaging app."
        const val GROUP_OPENED = "Group message draft opened for every recipient. Send it from your messaging app."

        /** Each number is encoded on its own, so only the separator EVA writes can ever join recipients. */
        fun messageIntent(
            recipients: List<String>,
            message: String,
        ): Intent =
            Intent(
                Intent.ACTION_SENDTO,
                "smsto:${recipients.joinToString(";") { Uri.encode(it) }}".toUri(),
            ).putExtra("sms_body", message)
    }
}
