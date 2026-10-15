package com.colonelpanic.eva.adapters.android

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * A non-UI in-call service. Telecom binds it for every call, alongside the owner's dialer, once EVA
 * holds the MANAGE_ONGOING_CALLS app op; it shows nothing and only tracks the calls so call tasks can
 * press keypad keys with the network's signalling, follow and hang up the right call.
 *
 * Non-UI in-call services are normally for companion apps; EVA uses one because `Call.playDtmfTone`
 * is the only app-reachable way to send out-of-band DTMF, which IVRs such as Twilio Gather act on
 * while ignoring in-band tones. Side effects of holding the app op: Telecom binds this service (and
 * so starts EVA's process) for every call on the phone, including calls EVA did not place, and the
 * op applies only to calls added after it was allowed; a reinstall can reset it. The manifest marks
 * the service as not UI, so the owner's dialer stays in charge of the call screen. Its manifest
 * entry includes self-managed calls, such as a VoIP app's, unless the app's account opts out.
 */
class CallControlService : InCallService() {
    override fun onCallAdded(call: Call) {
        mutableCalls.update { it + call }
    }

    override fun onCallRemoved(call: Call) {
        mutableCalls.update { it - call }
    }

    override fun onDestroy() {
        mutableCalls.value = emptyList()
        super.onDestroy()
    }

    companion object {
        private val mutableCalls = MutableStateFlow<List<Call>>(emptyList())
        val calls: StateFlow<List<Call>> = mutableCalls.asStateFlow()

        private const val OP_MANAGE_ONGOING_CALLS = "android:manage_ongoing_calls"
        private const val TAG = "EvaCallControl"

        @RequiresApi(33)
        fun existing(context: Context) =
            if (allowed(
                    context,
                )
            ) {
                CallControlAccess(
                    true,
                    null,
                )
            } else {
                CallControlAccess(false, "Android's manage-ongoing-calls access was off when this call was placed")
            }

        /** Telecom binds this service only for calls added after the app op is allowed, so grant before placing. */
        @RequiresApi(33)
        suspend fun grant(
            context: Context,
            helper: ICallAudio,
            granted: String,
        ): CallControlAccess {
            if (allowed(context)) return CallControlAccess(true, null)
            val failure =
                try {
                    withContext(Dispatchers.IO) { helper.allowCallControl(context.packageName) }
                } catch (error: Exception) {
                    error.message ?: error::class.simpleName
                }
            return if (failure == null && allowed(context)) {
                Log.i(TAG, "Allowed MANAGE_ONGOING_CALLS through Shizuku")
                CallControlAccess(true, granted)
            } else {
                CallControlAccess(
                    false,
                    "Shizuku could not allow EVA Android's manage-ongoing-calls access (${failure ?: "the app op stayed off"})",
                )
            }
        }

        @RequiresApi(33)
        fun allowed(context: Context): Boolean =
            context
                .getSystemService(AppOpsManager::class.java)
                .unsafeCheckOpNoThrow(OP_MANAGE_ONGOING_CALLS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }
}

/** Whether call control is on for a call; [note] says why not, or that EVA just turned it on. */
class CallControlAccess(
    val allowed: Boolean,
    val note: String?,
)
