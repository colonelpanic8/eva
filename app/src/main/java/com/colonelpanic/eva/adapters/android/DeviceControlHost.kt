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
import kotlinx.serialization.json.JsonObject
import java.lang.ref.WeakReference

/**
 * Owns the device-control user service. Deliberately separate from [ShizukuShellHost]: that host
 * gates every AppFunctions call on EVA being on screen, while screen control exists precisely to
 * run while another app is in front. Only the one-time permission prompt needs EVA's own surface.
 */
@SuppressLint("NewApi")
class DeviceControlHost(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private var surface: WeakReference<ComponentActivity>? = null
    private var service: IDeviceControl? = null
    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private var pendingBinding: CompletableDeferred<IDeviceControl>? = null
    private var permissionDenied = false

    private val serviceArgs =
        rikka.shizuku.Shizuku
            .UserServiceArgs(ComponentName(applicationContext.packageName, DeviceControlUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("device_control")
            .tag("eva-device-control")
            .version(1)

    private val permissionListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            permissionDenied = !granted
            pendingPermission?.complete(granted)
        }

    private val binderDeadListener =
        rikka.shizuku.Shizuku.OnBinderDeadListener {
            service = null
            pendingBinding?.completeExceptionally(ShizukuUnavailableException(SERVER_STOPPED))
        }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                binder: IBinder,
            ) {
                val connected = IDeviceControl.Stub.asInterface(binder)
                service = connected
                pendingBinding?.complete(connected)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
            }
        }

    init {
        rikka.shizuku.Shizuku.addRequestPermissionResultListener(permissionListener)
        rikka.shizuku.Shizuku.addBinderDeadListener(binderDeadListener)
    }

    fun attach(activity: ComponentActivity) {
        surface = WeakReference(activity)
    }

    fun detach(activity: ComponentActivity) {
        if (surface?.get() === activity) surface = null
    }

    suspend fun unavailableReason(): String? =
        withContext(Dispatchers.Main.immediate) {
            when {
                !isShizukuInstalled() -> NOT_INSTALLED
                !runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false) -> SERVER_STOPPED
                permissionGranted() -> null
                permissionDenied -> PERMISSION_DENIED
                resumedSurface() == null -> SURFACE_REQUIRED
                else -> null
            }
        }

    suspend fun observe(timeoutMillis: Long): String {
        val helper = withContext(Dispatchers.Main.immediate) { requireService() }
        return withContext(Dispatchers.IO) { helper.observe(timeoutMillis) }
    }

    suspend fun act(
        request: JsonObject,
        timeoutMillis: Long,
    ): String {
        val helper = withContext(Dispatchers.Main.immediate) { requireService() }
        return withContext(Dispatchers.IO) { helper.act(request.toString(), timeoutMillis) }
    }

    private suspend fun requireService(): IDeviceControl {
        if (!isShizukuInstalled()) throw ShizukuUnavailableException(NOT_INSTALLED)
        if (!runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)) {
            throw ShizukuUnavailableException(SERVER_STOPPED)
        }
        if (!permissionGranted()) {
            if (resumedSurface() == null) throw ShizukuUnavailableException(SURFACE_REQUIRED)
            if (!requestPermission()) throw ShizukuUnavailableException(PERMISSION_DENIED)
        }
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        pendingBinding?.let { return it.await() }
        val binding = CompletableDeferred<IDeviceControl>()
        pendingBinding = binding
        binding.invokeOnCompletion { if (pendingBinding === binding) pendingBinding = null }
        try {
            rikka.shizuku.Shizuku.bindUserService(serviceArgs, connection)
        } catch (error: Exception) {
            binding.completeExceptionally(ShizukuUnavailableException(error.message ?: SERVER_STOPPED))
        }
        return binding.await()
    }

    private suspend fun requestPermission(): Boolean {
        pendingPermission?.let { return it.await() }
        val permission = CompletableDeferred<Boolean>()
        pendingPermission = permission
        permission.invokeOnCompletion { if (pendingPermission === permission) pendingPermission = null }
        try {
            rikka.shizuku.Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (error: Exception) {
            permission.completeExceptionally(ShizukuUnavailableException(error.message ?: SERVER_STOPPED))
        }
        return permission.await()
    }

    private fun permissionGranted() =
        runCatching { rikka.shizuku.Shizuku.checkSelfPermission() }.getOrNull() == PackageManager.PERMISSION_GRANTED

    private fun resumedSurface() = surface?.get()?.takeIf { it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }

    private fun isShizukuInstalled(): Boolean =
        try {
            applicationContext.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    companion object {
        const val NOT_INSTALLED = "Shizuku is not installed. Install and start Shizuku to let EVA use the screen."
        const val SERVER_STOPPED = "Shizuku is not running. Start it before asking EVA to use the screen."
        const val PERMISSION_DENIED = "Shizuku access was denied. Allow EVA in Shizuku before trying again."
        const val SURFACE_REQUIRED = "Open EVA once to allow Shizuku access before it can use the screen."

        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val PERMISSION_REQUEST_CODE = 62118
    }
}
