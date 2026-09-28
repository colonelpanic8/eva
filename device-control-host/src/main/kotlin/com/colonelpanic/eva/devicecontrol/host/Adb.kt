package com.colonelpanic.eva.devicecontrol.host

import com.colonelpanic.eva.devicecontrol.portal.PortalBackend
import com.colonelpanic.eva.devicecontrol.portal.PortalClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

fun interface Adb {
    suspend fun command(args: List<String>): String
}

suspend fun Adb.shell(vararg args: String): String =
    command(
        listOf(
            "shell",
            args.joinToString(" ") {
                "'" + it.replace("'", "'\"'\"'") + "'"
            },
        ),
    )

class ProcessAdb(
    private val serial: String,
) : Adb {
    init {
        require(serial.isNotBlank() && !serial.startsWith('-'))
    }

    override suspend fun command(args: List<String>): String =
        withContext(Dispatchers.IO) {
            val process = ProcessBuilder(listOf("adb", "-s", serial) + args).redirectErrorStream(true).start()
            val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
            try {
                check(process.waitFor(30, TimeUnit.SECONDS)) { "adb command timed out" }
                // Do not include subprocess output: content-provider output can contain credentials.
                check(process.exitValue() == 0) { "adb command failed (exit ${process.exitValue()})" }
                output.get(2, TimeUnit.SECONDS).trim()
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
}

suspend fun verifyDevice(
    adb: Adb,
    serial: String,
    allowPhysical: String?,
): Boolean {
    val emulator =
        serial.startsWith("emulator-") &&
            (adb.shell("getprop", "ro.kernel.qemu") == "1" || adb.shell("getprop", "ro.boot.qemu") == "1")
    require(emulator || allowPhysical == serial) { "Physical device refused; use --allow-physical $serial explicitly" }
    return emulator
}

class DeviceSession private constructor(
    private val adb: Adb,
    private val port: Int,
    private val token: String,
    val emulator: Boolean,
    private val channel: FileChannel,
) : Closeable {
    fun backend() = PortalBackend(PortalClient(port = port, token = { token }))

    suspend fun disconnect() {
        try {
            adb.command(listOf("forward", "--remove", "tcp:$port"))
        } finally {
            close()
        }
    }

    override fun close() {
        channel.close()
    }

    companion object {
        suspend fun connect(
            adb: Adb,
            serial: String,
            allowPhysical: String?,
            stateDir: Path,
        ): DeviceSession {
            val emulator = verifyDevice(adb, serial, allowPhysical)
            stateDir.toFile().mkdirs()
            val safeName = serial.toByteArray().joinToString("") { "%02x".format(it) }
            val channel = FileChannel.open(stateDir.resolve("$safeName.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            var port: Int? = null
            try {
                check(channel.tryLock() != null) { "Another host command owns this device" }
                val output = adb.shell("content", "query", "--uri", "content://com.mobilerun.portal/auth_token")
                val token = portalToken(output)
                port = adb.command(listOf("forward", "tcp:0", "tcp:8080")).toInt()
                require(port in 1..65535)
                return DeviceSession(adb, port, token, emulator, channel)
            } catch (error: Exception) {
                try {
                    port?.let { adb.command(listOf("forward", "--remove", "tcp:$it")) }
                } finally {
                    channel.close()
                }
                throw error
            }
        }
    }
}

internal fun portalToken(output: String): String {
    val token =
        runCatching {
            val row = output.lineSequence().first { it.startsWith("Row: 0 result=") }.removePrefix("Row: 0 result=")
            val response = Json.parseToJsonElement(row).jsonObject
            check(response.getValue("status").jsonPrimitive.content == "success")
            response.getValue("result").jsonPrimitive.content
        }.getOrNull()
    check(!token.isNullOrBlank() && token != "null") { "Portal token unavailable; run Portal setup first" }
    return token
}
