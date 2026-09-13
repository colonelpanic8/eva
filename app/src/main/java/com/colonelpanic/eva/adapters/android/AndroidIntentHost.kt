package com.colonelpanic.eva.adapters.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

fun interface PermissionRequester {
    fun request(permission: String)
}

class AndroidIntentHost {
    private var surface: WeakReference<ComponentActivity>? = null
    private var requester: PermissionRequester? = null
    private var pendingPermission: CancellableContinuation<Boolean>? = null

    fun attach(
        activity: ComponentActivity,
        permissions: PermissionRequester? = null,
    ) {
        surface = WeakReference(activity)
        permissions?.let { requester = it }
    }

    fun detach(activity: ComponentActivity) {
        if (surface?.get() === activity) surface = null
    }

    /** Any activity instance may deliver the result; the dialog outlives a recreated surface. */
    fun onPermissionResult(granted: Boolean) {
        pendingPermission?.resume(granted)
        pendingPermission = null
    }

    suspend fun ensurePermission(permission: String): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val activity = resumedSurface() ?: return@withContext false
            if (ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED) return@withContext true
            val requester = requester ?: return@withContext false
            if (pendingPermission != null) return@withContext false
            suspendCancellableCoroutine { continuation ->
                pendingPermission = continuation
                continuation.invokeOnCancellation { if (pendingPermission === continuation) pendingPermission = null }
                requester.request(permission)
            }
        }

    suspend fun unavailableReason(): String? =
        withContext(Dispatchers.Main.immediate) {
            if (resumedSurface() == null) "Open EVA before sending this request." else null
        }

    suspend fun launch(
        intent: Intent,
        successMessage: String,
        missingAppMessage: String,
    ): ExecutionOutcome =
        withContext(Dispatchers.Main.immediate) {
            val activity = resumedSurface() ?: return@withContext ExecutionOutcome(InvocationStatus.NOT_EXECUTED, SURFACE_LOST)
            try {
                activity.startActivity(intent)
                ExecutionOutcome(InvocationStatus.HANDED_OFF, successMessage)
            } catch (_: ActivityNotFoundException) {
                ExecutionOutcome(InvocationStatus.FAILED, missingAppMessage)
            } catch (_: SecurityException) {
                ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "Android did not allow this request to open.")
            }
        }

    private fun resumedSurface() = surface?.get()?.takeIf { it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }

    companion object {
        const val SURFACE_LOST = "EVA lost the screen before the app could open. No app was opened. Try again."
    }
}
