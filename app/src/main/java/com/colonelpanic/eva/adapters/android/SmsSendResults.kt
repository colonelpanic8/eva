package com.colonelpanic.eva.adapters.android

import android.telephony.SmsManager
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

    /** A group text is one MMS to the whole thread, so it either reached the conversation or it did not. */
    fun describeGroup(
        destination: String,
        message: String,
        report: MmsSendReport,
    ): ExecutionOutcome =
        when {
            report.unsupported != null -> {
                ExecutionOutcome(
                    InvocationStatus.NOT_EXECUTED,
                    "The group text to $destination was not sent because ${report.unsupported}. " +
                        "Open a draft with the message-draft action instead; sending it to each person separately " +
                        "would start one-to-one threads rather than reach the group.",
                )
            }

            report.timedOut -> {
                ExecutionOutcome(
                    InvocationStatus.UNKNOWN,
                    "The network did not confirm the group text to $destination in time. It may still be delivered, " +
                        "so check the messaging app before sending it again.",
                )
            }

            report.errorCode != null -> {
                ExecutionOutcome(
                    InvocationStatus.FAILED,
                    "The group text to $destination was not sent: ${mmsFailureReason(report.errorCode)}.",
                )
            }

            else -> {
                ExecutionOutcome(
                    InvocationStatus.COMPLETED,
                    "Group text sent to $destination: \"${preview(message)}\".",
                )
            }
        }

    fun preview(message: String): String {
        val flattened = message.replace(Regex("\\s+"), " ").trim()
        return if (flattened.length <= MAX_PREVIEW) flattened else flattened.take(MAX_PREVIEW - 1).trimEnd() + "…"
    }

    fun mmsFailureReason(code: Int): String =
        when (code) {
            SmsManager.MMS_ERROR_INVALID_APN, SmsManager.MMS_ERROR_CONFIGURATION_ERROR -> {
                "this phone has no working multimedia messaging setup"
            }

            SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS -> {
                "the phone could not reach the carrier's messaging service"
            }

            SmsManager.MMS_ERROR_HTTP_FAILURE -> {
                "the carrier's messaging service rejected the message"
            }

            SmsManager.MMS_ERROR_IO_ERROR -> {
                "the message could not be read while sending"
            }

            SmsManager.MMS_ERROR_RETRY -> {
                "the carrier asked to try again later"
            }

            SmsManager.MMS_ERROR_NO_DATA_NETWORK -> {
                "there is no mobile data connection"
            }

            SmsManager.MMS_ERROR_INVALID_SUBSCRIPTION_ID, SmsManager.MMS_ERROR_INACTIVE_SUBSCRIPTION -> {
                "the SIM is not active"
            }

            SmsManager.MMS_ERROR_DATA_DISABLED -> {
                "mobile data is switched off"
            }

            SmsManager.MMS_ERROR_MMS_DISABLED_BY_CARRIER -> {
                "the carrier has multimedia messaging switched off"
            }

            SmsManager.MMS_ERROR_UNSPECIFIED -> {
                "the phone reported a generic multimedia messaging failure"
            }

            else -> {
                "Android reported MMS error code $code"
            }
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
