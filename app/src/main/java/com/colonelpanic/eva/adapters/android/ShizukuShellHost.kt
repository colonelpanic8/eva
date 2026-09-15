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

data class ShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timedOut: Boolean,
    val uid: Int,
)

class ShizukuUnavailableException(
    message: String,
) : IllegalStateException(message)

@SuppressLint("NewApi")
class ShizukuShellHost(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private var surface: WeakReference<ComponentActivity>? = null
    private var service: IAppFunctionsShell? = null
    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private var pendingBinding: CompletableDeferred<IAppFunctionsShell>? = null
    private var permissionDenied = false

    private val serviceArgs =
        Shizuku
            .UserServiceArgs(ComponentName(applicationContext.packageName, AppFunctionsUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("app_functions")
            .tag("eva-app-functions")
            .version(1)

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            permissionDenied = !granted
            pendingPermission?.complete(granted)
        }

    private val binderReceivedListener =
        Shizuku.OnBinderReceivedListener {
            if (runCatching { Shizuku.checkSelfPermission() }.getOrNull() == PackageManager.PERMISSION_GRANTED) {
                permissionDenied = false
            }
        }

    private val binderDeadListener =
        Shizuku.OnBinderDeadListener {
            service = null
            pendingBinding?.completeExceptionally(ShizukuUnavailableException(SERVER_STOPPED))
        }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                binder: IBinder,
            ) {
                val connected = IAppFunctionsShell.Stub.asInterface(binder)
                service = connected
                pendingBinding?.complete(connected)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
            }
        }

    init {
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
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
                !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> SERVER_STOPPED
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> null
                permissionDenied -> PERMISSION_DENIED
                resumedSurface() == null -> SURFACE_REQUIRED
                else -> null
            }
        }

    suspend fun run(
        args: List<String>,
        timeoutMillis: Long,
    ): ShellResult {
        require(args.size >= 2 && args[0] == "app_function" && args[1] == "execute-app-function")
        val helper = withContext(Dispatchers.Main.immediate) { requireService() }
        return withContext(Dispatchers.IO) {
            val result = helper.execute(args.toTypedArray(), timeoutMillis)
            ShellResult(
                stdout = result.getString(STDOUT).orEmpty(),
                stderr = result.getString(STDERR).orEmpty(),
                exitCode = result.getInt(EXIT_CODE, -1),
                timedOut = result.getBoolean(TIMED_OUT),
                uid = result.getInt(UID, -1),
            )
        }
    }

    private suspend fun requireService(): IAppFunctionsShell {
        unavailableReason()?.let { reason ->
            if (reason != PERMISSION_DENIED) throw ShizukuUnavailableException(reason)
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            if (resumedSurface() == null) throw ShizukuUnavailableException(SURFACE_REQUIRED)
            if (!requestPermission()) throw ShizukuUnavailableException(PERMISSION_DENIED)
        }
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        pendingBinding?.let { return it.await() }
        val binding = CompletableDeferred<IAppFunctionsShell>()
        pendingBinding = binding
        binding.invokeOnCompletion { if (pendingBinding === binding) pendingBinding = null }
        try {
            Shizuku.bindUserService(serviceArgs, connection)
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
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (error: Exception) {
            permission.completeExceptionally(ShizukuUnavailableException(error.message ?: SERVER_STOPPED))
        }
        return permission.await()
    }

    private fun resumedSurface() = surface?.get()?.takeIf { it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }

    private fun isShizukuInstalled(): Boolean =
        try {
            applicationContext.packageManager.getApplicationInfo(
                SHIZUKU_PACKAGE,
                PackageManager.ApplicationInfoFlags.of(0),
            )
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    companion object {
        const val STDOUT = "stdout"
        const val STDERR = "stderr"
        const val EXIT_CODE = "exitCode"
        const val TIMED_OUT = "timedOut"
        const val UID = "uid"

        const val NOT_INSTALLED = "Shizuku is not installed. Install and start Shizuku to use device-state actions."
        const val SERVER_STOPPED = "Shizuku is not running. Start it before using device-state actions."
        const val PERMISSION_DENIED = "Shizuku access was denied. Allow EVA in Shizuku before trying again."
        const val SURFACE_REQUIRED = "Open EVA once to allow Shizuku access before using device-state actions."

        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val PERMISSION_REQUEST_CODE = 62117
    }
}
