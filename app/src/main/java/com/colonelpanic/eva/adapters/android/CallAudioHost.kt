package com.colonelpanic.eva.adapters.android

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.lang.ref.WeakReference

/** Reaching the shell-UID call-audio helper. */
interface CallAudioAccess {
    /** Why the helper cannot be reached now, or null. */
    suspend fun unavailableReason(): String?

    suspend fun helper(): ICallAudio
}

/** Owns the call-audio user service. Like [ShizukuShellHost], only the permission prompt needs EVA's surface. */
@SuppressLint("NewApi")
class CallAudioHost(
    context: Context,
) : CallAudioAccess {
    private val applicationContext = context.applicationContext
    private var surface: WeakReference<ComponentActivity>? = null
    private var service: ICallAudio? = null
    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private var pendingBinding: CompletableDeferred<ICallAudio>? = null

    private val serviceArgs =
        Shizuku
            .UserServiceArgs(ComponentName(applicationContext.packageName, CallAudioUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("call_audio")
            .tag("eva-call-audio")
            .version(3)

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PERMISSION_REQUEST_CODE) pendingPermission?.complete(grantResult == PackageManager.PERMISSION_GRANTED)
        }

    private val binderDeadListener =
        Shizuku.OnBinderDeadListener {
            service = null
            pendingBinding?.completeExceptionally(ShizukuUnavailableException(ShizukuShellHost.SERVER_STOPPED))
        }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                binder: IBinder,
            ) {
                val connected = ICallAudio.Stub.asInterface(binder)
                service = connected
                pendingBinding?.complete(connected)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
            }
        }

    init {
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    fun attach(activity: ComponentActivity) {
        surface = WeakReference(activity)
    }

    fun detach(activity: ComponentActivity) {
        if (surface?.get() === activity) surface = null
    }

    override suspend fun unavailableReason(): String? =
        withContext(Dispatchers.Main.immediate) {
            when {
                !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> ShizukuShellHost.SERVER_STOPPED
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> null
                resumedSurface() == null -> ShizukuShellHost.SURFACE_REQUIRED
                else -> null
            }
        }

    /** The bound helper, requesting Shizuku access first if EVA is in front to ask. */
    override suspend fun helper(): ICallAudio =
        withContext(Dispatchers.Main.immediate) {
            unavailableReason()?.let { throw ShizukuUnavailableException(it) }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED && !requestPermission()) {
                throw ShizukuUnavailableException(ShizukuShellHost.PERMISSION_DENIED)
            }
            service?.takeIf { it.asBinder().isBinderAlive }?.let { return@withContext it }
            pendingBinding?.let { return@withContext it.await() }
            val binding = CompletableDeferred<ICallAudio>()
            pendingBinding = binding
            binding.invokeOnCompletion { if (pendingBinding === binding) pendingBinding = null }
            try {
                Shizuku.bindUserService(serviceArgs, connection)
            } catch (error: Exception) {
                binding.completeExceptionally(ShizukuUnavailableException(error.message ?: ShizukuShellHost.SERVER_STOPPED))
            }
            binding.await()
        }

    private suspend fun requestPermission(): Boolean {
        pendingPermission?.let { return it.await() }
        val permission = CompletableDeferred<Boolean>()
        pendingPermission = permission
        permission.invokeOnCompletion { if (pendingPermission === permission) pendingPermission = null }
        Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        return permission.await()
    }

    private fun resumedSurface() = surface?.get()?.takeIf { it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }

    private companion object {
        const val PERMISSION_REQUEST_CODE = 62119
    }
}
