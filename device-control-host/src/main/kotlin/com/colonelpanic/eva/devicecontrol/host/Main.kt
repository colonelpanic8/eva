@file:OptIn(kotlinx.cli.ExperimentalCli::class)

package com.colonelpanic.eva.devicecontrol.host

import com.colonelpanic.eva.devicecontrol.proto.ActionResult
import com.colonelpanic.eva.devicecontrol.proto.ProtocolJson
import com.colonelpanic.eva.devicecontrol.proto.renderTable
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.Subcommand
import kotlinx.cli.default
import kotlinx.cli.required
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import kotlin.system.exitProcess

private var exitStatus = 0

private abstract class DeviceCommand(
    name: String,
    description: String,
) : Subcommand(name, description) {
    val serial by option(ArgType.String, description = "Explicit adb serial").required()
    val allowPhysical by option(ArgType.String, fullName = "allow-physical", description = "Permit exactly this physical serial")
    val stateDir by option(
        ArgType.String,
        fullName = "state-dir",
        description = "Local locks, restore snapshots and run records",
    ).default(".device-control")

    fun connected(block: suspend (DeviceSession, Adb) -> Unit) =
        runBlocking {
            val adb = ProcessAdb(serial)
            val session = DeviceSession.connect(adb, serial, allowPhysical, Path.of(stateDir))
            try {
                block(session, adb)
            } finally {
                withContext(NonCancellable) { session.disconnect() }
            }
        }
}

private class Observe : DeviceCommand("observe", "Print a fresh compact observation") {
    override fun execute() {
        connected { session, _ -> println(session.backend().observe().renderTable()) }
    }
}

private class Act : DeviceCommand("act", "Observe, bind missing action envelope fields, and perform once") {
    val action by argument(ArgType.String, description = "Protocol v1 action JSON; omitted envelope fields bind to a fresh observation")

    override fun execute() {
        val template = Json.parseToJsonElement(action).jsonObject
        connected { session, _ ->
            val backend = session.backend()
            val result = backend.perform(bindAction(backend, template))
            println(ProtocolJson.encodeToString(ActionResult.serializer(), result))
            if (!result.ok) exitStatus = 1
        }
    }
}

fun main(args: Array<String>) {
    try {
        val parser = ArgParser("eva-device")
        parser.subcommands(Observe(), Act())
        parser.parse(args)
    } catch (error: Exception) {
        System.err.println("eva-device: ${error.message ?: error.javaClass.simpleName}")
        exitStatus = 2
    }
    exitProcess(exitStatus)
}
