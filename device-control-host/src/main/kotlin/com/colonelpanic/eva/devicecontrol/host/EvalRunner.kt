package com.colonelpanic.eva.devicecontrol.host

import com.colonelpanic.eva.devicecontrol.TaskAgent
import com.colonelpanic.eva.devicecontrol.TaskProgress
import com.colonelpanic.eva.devicecontrol.TaskResult
import com.colonelpanic.eva.devicecontrol.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

@Serializable
data class RunStep(
    val phase: String,
    val name: String,
    val millis: Long,
    val outcome: String,
)

@Serializable
data class RunRecord(
    val caseId: String,
    val agent: String,
    val outcome: String,
    val checkerVerdict: Verdict?,
    val steps: List<RunStep>,
    val startedAt: String,
    val durationMillis: Long,
    val taskStatus: String?,
    val taskSteps: Int,
    val error: String?,
    val teardownErrors: List<String>,
)

class EvalRunner(
    private val reset: DeviceReset,
    private val checkers: Checkers,
    private val emulator: Boolean,
) {
    suspend fun run(
        case: EvalCase,
        agentName: String,
        agent: TaskAgent,
    ): RunRecord {
        val start = System.nanoTime()
        val startedAt = Instant.now().toString()
        val steps = mutableListOf<RunStep>()
        var verdict: Verdict? = null
        var result: TaskResult? = null
        var error: String? = null
        var outcome = "failed"
        var phase = "reset"
        val teardownErrors = mutableListOf<String>()
        if (emulator && case.emulatorExclusion != null) {
            return RunRecord(
                case.id,
                agentName,
                "not_runnable",
                null,
                emptyList(),
                startedAt,
                0,
                null,
                0,
                case.emulatorExclusion,
                emptyList(),
            )
        }

        suspend fun measured(
            phase: String,
            name: String,
            block: suspend () -> Unit,
        ) {
            val before = System.nanoTime()
            var status = "failed"
            try {
                block()
                status = "completed"
            } finally {
                steps += RunStep(phase, name, (System.nanoTime() - before) / 1_000_000, status)
            }
        }
        try {
            withTimeout(case.timeoutMillis) {
                case.reset.forEach { spec -> measured("reset", spec.text("helper")) { reset.step(spec) } }
                phase = "task"
                measured("task", agentName) {
                    result =
                        agent.run(case.goal) { progress: TaskProgress ->
                            steps +=
                                RunStep(
                                    "agent",
                                    "${progress.step}:${progress.phase}",
                                    progress.timing.observationMillis + progress.timing.modelMillis + progress.timing.actionMillis,
                                    "progress",
                                )
                        }
                }
                phase = "checker"
                measured("checker", case.checker.text("name")) {
                    verdict = checkers.check(case.checker, if (agentName == "noop") null else result?.summary)
                }
                outcome =
                    when (result?.status) {
                        TaskStatus.UNKNOWN -> "unknown"
                        TaskStatus.CANCELLED -> "cancelled"
                        TaskStatus.FAILED -> "failed"
                        else -> if (verdict?.passed == true) "passed" else "failed"
                    }
            }
        } catch (failure: TimeoutCancellationException) {
            agent.cancel()
            outcome = "timeout"
            error = "$phase timed out; submitted effects may be uncertain"
        } catch (failure: CancellationException) {
            agent.cancel()
            throw failure
        } catch (failure: Exception) {
            outcome = "${phase}_error"
            error = failure.message ?: failure.javaClass.simpleName
        } finally {
            withContext(NonCancellable) {
                // Attempt every teardown step even if an earlier one failed.
                case.teardown.forEach { spec ->
                    try {
                        withTimeout(30_000) { measured("teardown", spec.text("helper")) { reset.step(spec) } }
                    } catch (
                        failure: Exception,
                    ) {
                        teardownErrors += "${spec.text("helper")}: ${failure.message}"
                    }
                }
                try {
                    withTimeout(30_000) { reset.restorePending() }
                } catch (
                    failure: Exception,
                ) {
                    teardownErrors += "pending restore: ${failure.message}"
                }
            }
        }
        if (teardownErrors.isNotEmpty()) outcome = "teardown_error"
        return RunRecord(
            case.id,
            agentName,
            outcome,
            verdict,
            steps,
            startedAt,
            (System.nanoTime() - start) / 1_000_000,
            result?.status?.name,
            result?.steps ?: 0,
            error,
            teardownErrors,
        )
    }
}

fun appendRecord(
    path: Path,
    record: RunRecord,
) {
    path
        .toAbsolutePath()
        .parent
        .toFile()
        .mkdirs()
    Files.writeString(path, Json.encodeToString(record) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
}
