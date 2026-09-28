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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.readText
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

private class Eval : Subcommand("eval", "Device evaluation harness") {
    init {
        subcommands(EvalRun())
    }

    override fun execute() = Unit
}

private class EvalRun : DeviceCommand("run", "Reset, run a TaskAgent, independently check, and tear down") {
    val casesRoot by option(ArgType.String, fullName = "cases", description = "Path to voice-device-agent/evals/cases").required()
    val families by option(ArgType.String, description = "Comma-separated settings,chrome_read,media").default("settings,chrome_read,media")
    val caseId by option(ArgType.String, fullName = "case", description = "Run one case id")
    val agentName by option(ArgType.Choice(listOf("noop", "scripted", "worker"), { it }), fullName = "agent").default("noop")
    val fixtureBaseUrl by option(ArgType.String, fullName = "fixture-base-url", description = "Fixture URL as reached by device")
    val scriptFile by option(ArgType.String, fullName = "script", description = "JSON map from case id to {actions: [...], answer: ...}")

    override fun execute() {
        val cases = loadCases(Path.of(casesRoot), families.split(',').toSet()).filter { caseId == null || it.id == caseId }
        require(cases.isNotEmpty()) { "No selected cases" }
        val factory = if (agentName == "worker") workerFactory() else null
        val scripts =
            if (agentName ==
                "scripted"
            ) {
                Json.parseToJsonElement(Path.of(requireNotNull(scriptFile) { "--agent scripted requires --script" }).readText()).jsonObject
            } else {
                JsonObject(emptyMap())
            }
        if (agentName == "scripted") cases.forEach { require(it.id in scripts) { "Missing script for ${it.id}" } }
        val runId = Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID().toString().take(8)
        val output = Path.of(stateDir, "runs", "$runId.jsonl")
        connected { session, adb ->
            val probes = DeviceProbes(adb, session.backend())
            val snapshotName = serial.toByteArray().joinToString("") { "%02x".format(it) }
            val reset = DeviceReset(adb, probes, fixtureBaseUrl, SnapshotStore(Path.of(stateDir, "snapshots", "$snapshotName.json")))
            reset.restorePending()
            val runner = EvalRunner(reset, Checkers(probes), session.emulator)
            for (case in cases) {
                val backend = session.backend()
                val agent =
                    when (agentName) {
                        "noop" -> NoopAgent()
                        "scripted" -> ScriptedAgent(backend, scripts.getValue(case.id).jsonObject, case.maxSteps)
                        else -> requireNotNull(factory).create(backend, case)
                    }
                val record = runner.run(case, agentName, agent)
                appendRecord(output, record)
                println("${case.id}: ${record.outcome}; ${record.checkerVerdict?.reason ?: record.error.orEmpty()}")
                if (record.outcome !in setOf("passed", "not_runnable")) exitStatus = 1
                if (reset.snapshots.pending().isNotEmpty()) error("Unrestored device state; inspect snapshots before continuing")
            }
        }
        println("JSONL: ${output.toAbsolutePath()}")
    }
}

fun main(args: Array<String>) {
    try {
        val parser = ArgParser("eva-device")
        parser.subcommands(Observe(), Act(), Eval())
        parser.parse(args)
    } catch (error: Exception) {
        System.err.println("eva-device: ${error.message ?: error.javaClass.simpleName}")
        exitStatus = 2
    }
    exitProcess(exitStatus)
}
