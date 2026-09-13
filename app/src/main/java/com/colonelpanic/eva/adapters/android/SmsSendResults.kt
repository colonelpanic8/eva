package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus

/** What the platform reported for one send attempt: parts submitted, the first failure, or no answer at all. */
data class SmsSendReport(
    val parts: Int,
    val failureCode: Int? = null,
    val timedOut: Boolean = false,
)

/** Turns a send attempt into the single sentence the model reads back, without overstating delivery. */
object SmsSendResults {
    const val MAX_PREVIEW = 80
    const val PERMISSION_DENIED =
        "Permission to send text messages was not granted. Allow it in EVA's app settings, or open a draft instead."
    const val NO_TELEPHONY = "This device has no cellular radio, so it cannot send a text message. Open a draft instead."
    const val UNSUPPORTED_ANDROID = "Sending a text message directly needs Android 12 or newer. Open a draft instead."
    const val NO_SMS_SERVICE = -1

    fun describe(
        recipient: String,
        message: String,
        report: SmsSendReport,
    ): ExecutionOutcome =
        when {
            report.timedOut -> {
                ExecutionOutcome(
                    InvocationStatus.UNKNOWN,
                    "The network did not confirm the text to $recipient in time. It may still be delivered, " +
                        "so check the messaging app before sending it again.",
                )
            }

            report.failureCode != null -> {
                ExecutionOutcome(
                    InvocationStatus.FAILED,
                    "The text to $recipient was not sent: ${failureReason(report.failureCode)}.",
                )
            }

            else -> {
                ExecutionOutcome(
                    InvocationStatus.COMPLETED,
                    "Text sent to $recipient: \"${preview(message)}\"" + if (report.parts > 1) " (${report.parts} parts)." else ".",
                )
            }
        }

    fun preview(message: String): String {
        val flattened = message.replace(Regex("\\s+"), " ").trim()
        return if (flattened.length <= MAX_PREVIEW) flattened else flattened.take(MAX_PREVIEW - 1).trimEnd() + "…"
    }

    fun failureReason(code: Int): String =
        when (code) {
            NO_SMS_SERVICE -> "this device exposes no SMS service"
            1 -> "the phone reported a generic sending failure"
            2 -> "the radio is off"
            3 -> "the message could not be encoded"
            4 -> "there is no cellular service"
            5 -> "too many messages were sent at once"
            6 -> "the SIM's fixed dialing list blocked the number"
            7, 8 -> "sending to that short code is not allowed"
            9 -> "the radio is unavailable"
            else -> "Android reported SMS error code $code"
        }
}
