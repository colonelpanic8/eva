package com.colonelpanic.eva.adapters.android

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

class AndroidIntentHost {
    private var surface: WeakReference<ComponentActivity>? = null

    fun attach(activity: ComponentActivity) {
        surface = WeakReference(activity)
    }

    fun detach(activity: ComponentActivity) {
        if (surface?.get() === activity) surface = null
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
