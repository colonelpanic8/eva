package com.colonelpanic.eva.adapters.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.Binder
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Runs inside the Shizuku user service as shell (UID 2000), never in EVA's own process. It accepts
 * only the fixed Portal-shaped primitives in [PortalCommands]; there is no general shell or planner
 * on this side.
 */
@RequiresApi(30)
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class DeviceControlUserService : IDeviceControl.Stub() {
    private class Connection(
        val thread: HandlerThread,
        val automation: UiAutomation,
    )

    private var connection: Connection? = null
    private var pendingRelease: ScheduledFuture<*>? = null
    private val reaper =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "eva-device-control-idle").apply { isDaemon = true }
        }

    override fun destroy() {
        exitProcess(0)
    }

    @Synchronized
    override fun state(timeoutMillis: Long): ParcelFileDescriptor {
        val reply =
            session(timeoutMillis) { automation, deadline ->
                buildJsonObject {
                    put("ok", true)
                    put("state", PortalState.capture(automation, minOf(deadline, SystemClock.elapsedRealtime() + ROOT_WAIT_MILLIS)))
                }
            }
        return stream(reply.toByteArray(Charsets.UTF_8), "eva-device-control-state")
    }

    @Synchronized
    override fun command(
        method: String,
        params: String,
        timeoutMillis: Long,
    ): String =
        session(timeoutMillis) { automation, deadline ->
            PortalCommands.run(automation, method, deviceControlJson.parseToJsonElement(params).jsonObject, deadline)
            buildJsonObject { put("ok", true) }
        }

    @Synchronized
    override fun screenshot(timeoutMillis: Long): ParcelFileDescriptor {
        val png = ByteArrayOutputStream()
        withAutomation(timeoutMillis) { automation, _ ->
            val bitmap = automation.takeScreenshot() ?: error("The screen could not be captured")
            try {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)) { "The screen could not be encoded" }
            } finally {
                bitmap.recycle()
            }
        }
        return stream(png.toByteArray(), "eva-device-control-screenshot")
    }

    private fun stream(
        bytes: ByteArray,
        name: String,
    ): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe()
        Thread({
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(bytes) }
        }, name).start()
        return read
    }

    private fun session(
        timeoutMillis: Long,
        block: (UiAutomation, Long) -> JsonObject,
    ): String =
        try {
            withAutomation(timeoutMillis, block)
        } catch (error: Exception) {
            buildJsonObject {
                put("ok", false)
                put("reason", "error")
                put("detail", "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(MAX_DETAIL_CHARS))
            }
        }.toString()

    /**
     * One UiAutomation connection serves consecutive calls; it is released after a short idle period.
     * The connection trusts only the UID that connected it, so every call, including the idle
     * reaper's disconnect, runs as shell rather than as the calling EVA process.
     */
    private fun <T> withAutomation(
        timeoutMillis: Long,
        block: (UiAutomation, Long) -> T,
    ): T {
        require(Process.myUid() == SHELL_UID) { "Device control must run as Android's shell user" }
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "Invalid timeout" }
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        pendingRelease?.cancel(false)
        val identity = Binder.clearCallingIdentity()
        try {
            val current = connection ?: open().also { connection = it }
            return block(current.automation, deadline)
        } catch (error: Exception) {
            if (error !is PortalCommands.Failure) release()
            throw error
        } finally {
            Binder.restoreCallingIdentity(identity)
            pendingRelease = reaper.schedule({ synchronized(this) { release() } }, IDLE_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private fun open(): Connection {
        val thread = HandlerThread("eva-device-control")
        thread.start()
        return try {
            Connection(thread, connect(thread.looper))
        } catch (error: Exception) {
            thread.quitSafely()
            throw error
        }
    }

    private fun release() {
        val current = connection ?: return
        connection = null
        runCatching { UiAutomation::class.java.getMethod("disconnect").invoke(current.automation) }
            .onFailure { Log.w(TAG, "UiAutomation did not disconnect", it) }
        current.thread.quitSafely()
    }

    /** The hidden constructor the platform's own uiautomator command uses from a shell process. */
    private fun connect(looper: Looper): UiAutomation {
        val connectionType = Class.forName("android.app.IUiAutomationConnection")
        val connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance()
        val automation =
            UiAutomation::class.java
                .getConstructor(Looper::class.java, connectionType)
                .newInstance(looper, connection)
        try {
            UiAutomation::class.java
                .getMethod("connect", Int::class.javaPrimitiveType)
                .invoke(automation, FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        return automation
    }

    private companion object {
        const val TAG = "EvaDeviceControl"
        const val SHELL_UID = 2000
        const val MAX_TIMEOUT_MILLIS = 30_000L
        const val MAX_DETAIL_CHARS = 300
        const val IDLE_MILLIS = 20_000L
        const val ROOT_WAIT_MILLIS = 1_000L
        const val FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES = 0x1
    }
}

/** Shared JSON configuration for the helper boundary; unknown keys must not crash an action. */
internal val deviceControlJson = Json { ignoreUnknownKeys = true }
