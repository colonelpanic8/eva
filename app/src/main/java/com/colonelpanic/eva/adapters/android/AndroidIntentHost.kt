package com.colonelpanic.eva.adapters.android

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.colonelpanic.eva.assist.AssistantRole
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

fun interface PermissionRequester {
    fun request(permission: String)
}

/** A shown assistant session that can ask Android to open an app. */
fun interface AssistantLauncher {
    fun start(intent: Intent)
}

class AndroidIntentHost(
    private val context: Context,
    private val backgroundAssistantAvailable: () -> Boolean = { AssistantRole.isEva(context) },
    private val deviceLocked: () -> Boolean = { context.getSystemService(KeyguardManager::class.java).isDeviceLocked },
    private val requestUnlock: suspend (ComponentActivity) -> Boolean? = ::dismissKeyguard,
) {
    private var surface: WeakReference<ComponentActivity>? = null
    private var assistant: AssistantLauncher? = null
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

    fun attachAssistant(launcher: AssistantLauncher) {
        assistant = launcher
    }

    fun detachAssistant(launcher: AssistantLauncher) {
        if (assistant === launcher) assistant = null
    }

    /** Any activity instance may deliver the result; the dialog outlives a recreated surface. */
    fun onPermissionResult(granted: Boolean) {
        pendingPermission?.resume(granted)
        pendingPermission = null
    }

    suspend fun ensurePermission(permission: String): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) return@withContext true
            if (resumedSurface() == null) return@withContext false
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
            if (starter() == null) SURFACE_LOST else null
        }

    suspend fun permissionUnavailableReason(permission: String): String? =
        withContext(Dispatchers.Main.immediate) {
            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED || resumedSurface() != null) {
                null
            } else {
                "Open EVA to grant the required Android permission before sending this request."
            }
        }

    /**
     * With [unlockFirst], a locked phone showing EVA's own screen is asked to unlock before the app
     * opens, since the target could only wait behind the lock screen. Declining opens nothing. Without
     * an EVA screen to ask from, the launch proceeds as usual and Android decides.
     */
    suspend fun launch(
        intent: Intent,
        successMessage: String,
        missingAppMessage: String,
        unlockFirst: Boolean = false,
    ): ExecutionOutcome =
        withContext(Dispatchers.Main.immediate) {
            if (unlockFirst && deviceLocked()) {
                resumedSurface()?.let { activity ->
                    if (withTimeoutOrNull(UNLOCK_WAIT_MILLIS) { requestUnlock(activity) } == false) {
                        return@withContext ExecutionOutcome(InvocationStatus.NOT_EXECUTED, STAYED_LOCKED)
                    }
                }
            }
            val start = starter() ?: return@withContext ExecutionOutcome(InvocationStatus.NOT_EXECUTED, SURFACE_LOST)
            try {
                start(intent)
                ExecutionOutcome(
                    InvocationStatus.HANDED_OFF,
                    if (deviceLocked()) {
                        "$successMessage The phone is locked; unlock to view or finish in the target app. Completion is not verified."
                    } else {
                        successMessage
                    },
                )
            } catch (_: ActivityNotFoundException) {
                ExecutionOutcome(
                    if (intent.component !=
                        null
                    ) {
                        InvocationStatus.NOT_EXECUTED
                    } else {
                        InvocationStatus.FAILED
                    },
                    missingAppMessage,
                )
            } catch (_: IllegalStateException) {
                ExecutionOutcome(InvocationStatus.NOT_EXECUTED, SURFACE_LOST)
            } catch (_: SecurityException) {
                ExecutionOutcome(
                    InvocationStatus.NOT_EXECUTED,
                    if (deviceLocked()) UNLOCK_REQUIRED else "Android did not allow this request to open.",
                )
            }
        }

    private fun resumedSurface() = surface?.get()?.takeIf { it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }

    /** EVA's own screen when it has one, the assistant session when it does not. */
    private fun starter(): ((Intent) -> Unit)? {
        resumedSurface()?.let { activity -> return activity::startActivity }
        assistant?.let { launcher -> return launcher::start }
        if (backgroundAssistantAvailable()) {
            return { intent -> context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        return null
    }

    companion object {
        const val UNLOCK_WAIT_MILLIS = 60_000L
        const val STAYED_LOCKED = "The phone stayed locked, so the app was not opened. Unlock and ask again."

        /** True once unlocked, false when the user backs out, null when Android cannot ask from here. */
        private suspend fun dismissKeyguard(activity: ComponentActivity): Boolean? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
            val keyguard = activity.getSystemService(KeyguardManager::class.java) ?: return null
            return suspendCancellableCoroutine { continuation ->
                keyguard.requestDismissKeyguard(
                    activity,
                    object : KeyguardManager.KeyguardDismissCallback() {
                        override fun onDismissSucceeded() {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onDismissCancelled() {
                            if (continuation.isActive) continuation.resume(false)
                        }

                        override fun onDismissError() {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            }
        }

        const val SURFACE_LOST =
            "This action opens another app. Invoke EVA through the system assistant " +
                "or select EVA as the default assistant, then try again. Nothing was opened."
        const val UNLOCK_REQUIRED =
            "Android refused to open the target app while the phone was locked. Unlock and try again. Nothing was opened."
    }
}
