package com.colonelpanic.eva.adapters.android

import android.os.Bundle
import android.os.Process
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

@RequiresApi(37)
class AppFunctionsUserService : IAppFunctionsShell.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun execute(
        args: Array<out String>,
        timeoutMillis: Long,
    ): Bundle {
        require(Process.myUid() == SHELL_UID) { "AppFunctions service must run as shell" }
        requireAllowedCommand(args)
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "Invalid timeout" }

        val readers = Executors.newFixedThreadPool(2)
        return try {
            val process = ProcessBuilder(listOf(CMD) + args).start()
            val stdout = readers.submit<String> { process.inputStream.readBounded() }
            val stderr = readers.submit<String> { process.errorStream.readBounded() }
            val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(200, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
            Bundle().apply {
                putString(ShizukuShellHost.STDOUT, runCatching { stdout.get(2, TimeUnit.SECONDS) }.getOrDefault(""))
                putString(ShizukuShellHost.STDERR, runCatching { stderr.get(2, TimeUnit.SECONDS) }.getOrDefault(""))
                putInt(ShizukuShellHost.EXIT_CODE, if (finished) process.exitValue() else -1)
                putBoolean(ShizukuShellHost.TIMED_OUT, !finished)
                putInt(ShizukuShellHost.UID, Process.myUid())
            }
        } catch (error: Exception) {
            Bundle().apply {
                putString(ShizukuShellHost.STDOUT, "")
                putString(ShizukuShellHost.STDERR, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                putInt(ShizukuShellHost.EXIT_CODE, -1)
                putBoolean(ShizukuShellHost.TIMED_OUT, false)
                putInt(ShizukuShellHost.UID, Process.myUid())
            }
        } finally {
            readers.shutdownNow()
        }
    }

    private fun InputStream.readBounded(): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var kept = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            val writable = minOf(count, MAX_OUTPUT_BYTES - kept)
            if (writable > 0) {
                output.write(buffer, 0, writable)
                kept += writable
            }
        }
        return output.toString(StandardCharsets.UTF_8)
    }

    private fun requireAllowedCommand(args: Array<out String>) {
        require(
            args.size == 11 &&
                args[0] == "app_function" &&
                args[1] == "execute-app-function" &&
                args[2] == "--package" &&
                args[3] == SETTINGS_PACKAGE &&
                args[4] == "--function" &&
                args[5] in ALLOWED_FUNCTIONS &&
                args[6] == "--parameters" &&
                args[7].length <= MAX_PARAMETERS_LENGTH &&
                args[8] == "--timeout-duration" &&
                args[9] == "8" &&
                args[10] == "--brief-yaml",
        ) { "Unsupported command" }
    }

    private companion object {
        const val CMD = "/system/bin/cmd"
        const val SETTINGS_PACKAGE = "com.android.settings"
        const val SHELL_UID = 2000
        const val MAX_TIMEOUT_MILLIS = 30_000L
        const val MAX_OUTPUT_BYTES = 512 * 1024
        const val MAX_PARAMETERS_LENGTH = 1_024
        val ALLOWED_FUNCTIONS =
            setOf(
                "getBatteryDeviceState",
                "getStorageDeviceState",
                "getNotificationsDeviceState",
                "getAppsDeviceState",
                "getMobileDataUsageDeviceState",
                "getUncategorizedDeviceState",
                "getDeviceStateMetadata",
                "setDeviceStateItem",
            )
    }
}
