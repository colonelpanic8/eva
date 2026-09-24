package com.colonelpanic.eva.adapters.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus

/**
 * Places the call through Android's telecom service when EVA holds the phone permission, so it
 * needs no screen of EVA's and works over the lock screen. Without the permission, and for
 * emergency numbers only the dialer app may place, it opens the dialer with the number instead.
 */
class PhoneCallBackend(
    private val context: Context,
    private val dialer: ExecutionBackend,
    private val isEmergency: (String) -> Boolean = { emergency(context, it) },
) : ExecutionBackend {
    private fun canCall(number: String) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
            !isEmergency(number)

    override suspend fun unavailableReason(): String? =
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            null
        } else {
            dialer.unavailableReason()?.let { "$it $PERMISSION_HINT" }
        }

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
        val number = arguments.getValue("number")
        if (!canCall(number)) {
            val opened = dialer.execute(arguments)
            if (opened.status != InvocationStatus.HANDED_OFF || isEmergency(number)) return opened
            return opened.copy(message = "${opened.message} The user still has to press call. $PERMISSION_HINT")
        }
        val telecom =
            context.getSystemService(TelecomManager::class.java)
                ?: return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "This device cannot place phone calls. Nothing was dialed.")
        return try {
            telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
            ExecutionOutcome(
                InvocationStatus.HANDED_OFF,
                "Android's phone service is placing the call to $number. EVA does not see whether it connects.",
            )
        } catch (_: SecurityException) {
            ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "Android did not let EVA place the call. Nothing was dialed.")
        }
    }

    companion object {
        const val PERMISSION_HINT = "Grant EVA the Phone permission to place calls directly, including on a locked phone."

        /** An undecidable number counts as an emergency, so it goes to the dialer rather than failing silently. */
        fun emergency(
            context: Context,
            number: String,
        ): Boolean =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(number) ?: true
                } else {
                    @Suppress("DEPRECATION")
                    PhoneNumberUtils.isEmergencyNumber(number)
                }
            }.getOrDefault(true)
    }
}
