package com.colonelpanic.eva.devicecontrol.worker

import com.colonelpanic.eva.devicecontrol.DeviceBackend
import com.colonelpanic.eva.devicecontrol.PreferredDeviceBackend
import com.colonelpanic.eva.devicecontrol.StepTiming
import com.colonelpanic.eva.devicecontrol.TaskAgent
import com.colonelpanic.eva.devicecontrol.TaskPhase
import com.colonelpanic.eva.devicecontrol.TaskProgress
import com.colonelpanic.eva.devicecontrol.TaskResult
import com.colonelpanic.eva.devicecontrol.TaskStatus
import com.colonelpanic.eva.devicecontrol.proto.Action
import com.colonelpanic.eva.devicecontrol.proto.ActionResult
import com.colonelpanic.eva.devicecontrol.proto.Element
import com.colonelpanic.eva.devicecontrol.proto.ExecutionStatus
import com.colonelpanic.eva.devicecontrol.proto.Observation
import com.colonelpanic.eva.devicecontrol.proto.ProtocolJson
import com.colonelpanic.eva.devicecontrol.proto.Screenshot
import com.colonelpanic.eva.devicecontrol.proto.ScreenshotDetails
import com.colonelpanic.eva.devicecontrol.proto.Scroll
import com.colonelpanic.eva.devicecontrol.proto.ScrollDirection
import com.colonelpanic.eva.devicecontrol.proto.SetTextDetails
import com.colonelpanic.eva.devicecontrol.proto.renderTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/** A single task. Control methods latch under a short monitor, never an execution mutex. */
class TextTaskAgent(
    private val backend: DeviceBackend,
    private val model: WorkerModel,
    private val wording: WorkerWording,
    private val settings: WorkerSettings = WorkerSettings(),
    val taskId: String = UUID.randomUUID().toString(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : TaskAgent {
    private val control = Any()
    private val mailbox = Channel<Unit>(Channel.CONFLATED)
    private val revisions = mutableListOf<String>()
    private var revisionNumber = 0L
    private var stopped = false
    private var started = false
    private var pending: Job? = null
    private var paused = false
    val revision: Long get() = synchronized(control) { revisionNumber }
    val isStopped: Boolean get() = synchronized(control) { stopped }
    val steps = mutableListOf<WorkerStep>()
    var effects = 0
        private set
    private var uncertain = false

    override fun revise(correction: String) {
        require(correction.isNotBlank())
        synchronized(control) {
            revisionNumber++
            revisions += correction
            if (revisions.size > settings.historyLines) revisions.removeAt(0)
            paused = false
            pending?.cancel()
            mailbox.trySend(Unit)
        }
    }

    fun pauseForCorrection() =
        synchronized(control) {
            paused = true
            pending?.cancel()
        }

    override fun cancel() =
        synchronized(control) {
            stopped = true
            pending?.cancel()
            mailbox.trySend(Unit)
            Unit
        }

    private suspend fun <T> interruptible(block: suspend () -> T): T =
        coroutineScope {
            val job = async(start = CoroutineStart.LAZY) { block() }
            synchronized(control) {
                if (stopped || paused) {
                    job.cancel()
                } else {
                    pending = job
                    job.start()
                }
            }
            try {
                job.await()
            } finally {
                synchronized(control) { if (pending === job) pending = null }
            }
        }

    override suspend fun run(
        goal: String,
        onProgress: (TaskProgress) -> Unit,
    ): TaskResult {
        synchronized(control) {
            check(!started)
            started = true
        }
        var step = 0
        var seenRevision = -1L
        var observation: Observation? = null
        var observationMillis = 0L
        var noCall = 0
        var refused = 0
        var noChange = 0
        var screenshots = 0
        var reversals = 0
        var lastScroll: ScrollState? = null
        val repeats = mutableMapOf<String, Int>()
        var messages = mutableListOf<WorkerMessage>()
        var pendingReply: WorkerReply? = null
        var exchange = emptyList<WorkerMessage>()
        val callHistory = mutableMapOf<Int, String>()
        var screens = 0
        var note = ""
        var image: String? = null
        var waited = 0L
        val begin = clock()

        fun remaining() = settings.maxMillis - (clock() - begin - waited)

        fun result(
            status: TaskStatus,
            summary: String,
        ) = TaskResult(taskId, revision, if (uncertain) TaskStatus.UNKNOWN else status, summary, step)

        fun progress(
            phase: TaskPhase,
            message: String? = null,
            timing: StepTiming = StepTiming(),
        ) = onProgress(TaskProgress(taskId, revision, step, phase, message, timing))
        try {
            while (step < settings.maxSteps && remaining() > 0) {
                if (isStopped) return result(TaskStatus.CANCELLED, "cancelled")
                if (synchronized(control) { paused }) {
                    val wait = clock()
                    mailbox.receive()
                    waited += clock() - wait
                    continue
                }
                val rev = revision
                if (observation == null || seenRevision != rev) {
                    progress(TaskPhase.OBSERVING)
                    val start = clock()
                    try {
                        observation = interruptible { withTimeout(remaining()) { backend.observe() } }
                    } catch (
                        e: CancellationException,
                    ) {
                        currentCoroutineContext().ensureActive()
                        if (isStopped || revision != rev ||
                            synchronized(control) { paused }
                        ) {
                            continue
                        }
                        throw e
                    }
                    observationMillis = clock() - start
                    if (revision != rev || isStopped) continue
                    seenRevision = rev
                    messages.clear()
                    screens = 0
                    lastScroll = null
                }
                val before = checkNotNull(observation)
                pendingReply?.let { previous ->
                    val output =
                        previous.output.ifEmpty {
                            previous.calls.map { WorkerMessage("assistant", "", call = it) }
                        }
                    exchange = output +
                        previous.calls.mapIndexed { index, call ->
                            WorkerMessage("tool", if (index == 0) note else wording.note("extra_call"), resultFor = call.id)
                        }
                    pendingReply = null
                    if (previous.calls.isNotEmpty()) note = ""
                }
                if (screens >= settings.maxScreens || messages.isEmpty()) {
                    val corrections = synchronized(control) { revisions.joinToString("\n") }
                    messages =
                        mutableListOf(
                            WorkerMessage(
                                "user",
                                wording.note("task", "goal" to goal, "revisions" to corrections) +
                                    if (corrections.isBlank()) "" else "\n" + wording.note("revisions", "revisions" to corrections),
                            ),
                        )
                    if (steps.isNotEmpty()) {
                        val earlier = steps.dropLast(if (exchange.isEmpty()) 0 else 1)
                        val shown = earlier.takeLast(settings.historyLines)
                        messages +=
                            WorkerMessage(
                                "user",
                                wording.note("history") + "\n" +
                                    (
                                        if (shown.size < earlier.size) {
                                            wording.note("history_omitted", "count" to earlier.size - shown.size) + "\n"
                                        } else {
                                            ""
                                        }
                                    ) +
                                    shown.joinToString("\n") {
                                        "step ${it.step}: ${callHistory[it.step] ?: it.kind} → ${it.result}"
                                    },
                            )
                    }
                    screens = 0
                }
                messages += exchange
                exchange = emptyList()
                messages +=
                    WorkerMessage(
                        "user",
                        wording.note("screen", "step" to (step + 1), "observation_id" to before.observationId) + "\n" + note + "\n" +
                            before.renderTable(contentNotice = wording.note("screen_content")),
                        image,
                    )
                screens++
                note = ""
                image = null
                progress(TaskPhase.THINKING)
                val modelStart = clock()
                val reply =
                    try {
                        interruptible {
                            withTimeout(minOf(remaining(), settings.modelTimeoutMillis)) {
                                model.complete(WorkerRequest(wording.instructions, messages.toList(), wording.tools, "eva-device-$taskId"))
                            }
                        }
                    } catch (
                        e: CancellationException,
                    ) {
                        currentCoroutineContext().ensureActive()
                        if (isStopped || revision != rev ||
                            synchronized(control) { paused }
                        ) {
                            continue
                        }
                        throw e
                    }
                val modelMillis = clock() - modelStart
                if (messages.any { it.png != null }) messages.clear()
                step++
                if (isStopped || revision != rev || synchronized(control) { paused }) continue
                pendingReply = reply
                val call = reply.calls.firstOrNull()
                if (call == null) {
                    steps +=
                        WorkerStep(
                            step,
                            rev,
                            "invalid_tool_count",
                            "not_dispatched",
                            StepTiming(observationMillis, modelMillis),
                            detail = "No action chosen",
                        )
                    note = wording.note("one_call")
                    noCall++
                    if (noCall >= 2) return result(TaskStatus.FAILED, "no_tool_call")
                    continue
                }
                noCall = 0
                // Redact secret text in replayed calls as well as progress history.
                val safeArgs =
                    if (call.name == "set_text" &&
                        before.elements.getOrNull(call.arguments["element"]?.jsonPrimitive?.intOrNull ?: -1)?.password == true
                    ) {
                        JsonObject(call.arguments + ("text" to JsonPrimitive("<password>")))
                    } else {
                        call.arguments
                    }
                steps +=
                    WorkerStep(
                        step,
                        rev,
                        call.name,
                        "not_dispatched",
                        StepTiming(observationMillis, modelMillis),
                        call.id,
                        call.responseId,
                        call.outputItemId,
                        detail = describe(call.name, safeArgs, before),
                        intent = safeArgs.text("intent"),
                        backend = activeBackend(),
                    )
                callHistory[step] = "${call.name}(${clip(safeArgs.toString(), MAX_REPLAYED_ARGS)})"
                if (safeArgs != call.arguments) {
                    val redacted = call.copy(arguments = safeArgs)
                    pendingReply = reply.copy(calls = listOf(redacted) + reply.calls.drop(1), output = emptyList())
                }
                if (call.name == "ask_user") {
                    val question =
                        call.arguments["question"]
                            ?.jsonPrimitive
                            ?.content
                            .orEmpty()
                            .let { clip(it, MAX_QUESTION) }
                    if (question.isBlank()) {
                        note = wording.note("invalid_call")
                        refused++
                        continue
                    }
                    steps[steps.lastIndex] = steps.last().copy(result = "asked")
                    progress(TaskPhase.NEEDS_INPUT, question, StepTiming(observationMillis, modelMillis))
                    val wait = clock()
                    while (!isStopped && revision == rev) mailbox.receive()
                    waited += clock() - wait
                    val replies = synchronized(control) { revisions.takeLast((revisionNumber - rev).toInt()) }
                    note = wording.note("answer", "reply" to replies.joinToString("\n"))
                    continue
                }
                if (call.name == "finish") {
                    val status = call.arguments["status"]?.jsonPrimitive?.content
                    val summary =
                        call.arguments["summary"]
                            ?.jsonPrimitive
                            ?.content
                            .orEmpty()
                            .let(::boundedSummary)
                    steps[steps.lastIndex] = steps.last().copy(result = status.orEmpty())
                    return result(if (status == "completed") TaskStatus.COMPLETED else TaskStatus.FAILED, summary)
                }
                val key = call.name + JsonObject(safeArgs.filterKeys { it != "intent" }).toString() + signature(before)
                if ((repeats[key] ?: 0) >= 2) return result(TaskStatus.FAILED, "loop_detected")
                var action: Action? = null
                try {
                    if (call.name != "observe") action = action(call, before, rev)
                } catch (
                    _: Exception,
                ) {
                    note = wording.note("invalid_call")
                    refused++
                    if (refused >=
                        settings.maxRefusals
                    ) {
                        return result(TaskStatus.FAILED, "refusal_limit")
                    }
                    continue
                }
                if (action is Scroll) {
                    val previous = lastScroll
                    if (previous != null && previous.target == label(before, action.element) &&
                        opposite(previous.direction) == action.direction
                    ) {
                        if (reversals >= 2 || (previous.productive && !previous.found)) {
                            note = wording.note("scroll_reversal", "previous" to previous.direction.name.lowercase())
                            refused++
                            if (refused >= settings.maxRefusals) return result(TaskStatus.FAILED, "refusal_limit")
                            continue
                        }
                        reversals++
                    }
                }
                if (action is Screenshot && screenshots >= settings.maxScreenshots) {
                    note = wording.note("screenshot_limit")
                    refused++
                    if (refused >= settings.maxRefusals) return result(TaskStatus.FAILED, "refusal_limit")
                    continue
                }
                if (remaining() <= 0) return result(TaskStatus.FAILED, "max_time")
                progress(TaskPhase.ACTING, call.name)
                val actionStart = clock()
                var actionResult: ActionResult? = null
                var performEntered = false
                // Publishing the child under the monitor makes stop/revise win before dispatch.
                try {
                    interruptible {
                        if (revision != rev || isStopped) throw CancellationException()
                        if (action == null) {
                            observation = backend.observe()
                        } else {
                            performEntered = true
                            val r = backend.perform(action)
                            actionResult = r
                            if (r.executionStatus == ExecutionStatus.EXECUTED && action !is Screenshot) effects++
                            if (r.executionStatus in setOf(ExecutionStatus.OUTCOME_UNKNOWN, ExecutionStatus.IN_FLIGHT)) uncertain = true
                            observation = r.observation ?: backend.observe()
                        }
                    }
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    // A backend drains submitted input before returning from cancellation.
                    if (performEntered && actionResult == null) uncertain = true
                    if (!isStopped && revision == rev && !synchronized(control) { paused }) throw e
                }
                val elapsed = clock() - actionStart
                val timing =
                    if (action ==
                        null
                    ) {
                        StepTiming(observationMillis + elapsed, modelMillis, 0)
                    } else {
                        StepTiming(observationMillis, modelMillis, elapsed)
                    }
                observationMillis = 0
                val r = actionResult
                val aliasedLaunch =
                    action is com.colonelpanic.eva.devicecontrol.proto.LaunchApp &&
                        observation?.packageName in settings.launchAliases[action.packageName].orEmpty() &&
                        r?.error is com.colonelpanic.eva.devicecontrol.proto.AppNotFound
                val outcome = if (aliasedLaunch) "ok" else r?.error?.javaClass?.simpleName ?: if (r?.ok == false) "failed" else "ok"
                steps[steps.lastIndex] = steps.last().copy(result = outcome, timing = timing, backend = activeBackend())
                progress(TaskPhase.PROGRESS, "${call.name}: $outcome", timing)
                if (isStopped) return result(TaskStatus.CANCELLED, "cancelled")
                if (revision != rev) continue
                if (r?.executionStatus != ExecutionStatus.NOT_DISPATCHED) repeats[key] = (repeats[key] ?: 0) + 1
                refused = if (r?.executionStatus == ExecutionStatus.NOT_DISPATCHED) refused + 1 else 0
                if (refused >= settings.maxRefusals) return result(TaskStatus.FAILED, "refusal_limit")
                val after = checkNotNull(observation)
                if (action != null && action !is Screenshot &&
                    r?.executionStatus != ExecutionStatus.NOT_DISPATCHED
                ) {
                    noChange =
                        if (signature(before) == signature(after)) noChange + 1 else 0
                }
                if (noChange >= 5) return result(TaskStatus.FAILED, "loop_detected")
                note = wording.note("result", "kind" to call.name, "result" to outcome)
                if (reply.calls.size > 1) note += "\n" + wording.note("one_call")
                if (r?.details is SetTextDetails) {
                    val d = r.details as SetTextDetails
                    note =
                        if (d.verified) {
                            wording.note("text_verified")
                        } else {
                            wording.note(
                                "text_result",
                                "verified" to d.verified,
                                "expected" to JsonPrimitive(d.expected ?: "<password>"),
                                "actual" to JsonPrimitive(d.actual ?: "<password>"),
                            )
                        }
                }
                if (r?.details is ScreenshotDetails) {
                    note = wording.note("screenshot_attached")
                    screenshots++
                    image = (r.details as ScreenshotDetails).png
                    messages.clear()
                }
                if (action is Scroll && r?.ok == true) {
                    val old = before.elements.map(::identity).toSet()
                    val fresh =
                        after.elements.filter {
                            identity(it) !in old && (
                                it.password || !it.text.isNullOrEmpty() || !it.contentDescription.isNullOrEmpty() ||
                                    !it.resourceId.isNullOrEmpty() || it.checkable || it.selected
                            )
                        }
                    val labels = fresh.map(::elementLabel)
                    val words = words(goal + synchronized(control) { revisions.joinToString(" ") })
                    lastScroll =
                        ScrollState(
                            action.direction,
                            label(before, action.element),
                            labels.isNotEmpty(),
                            labels.any {
                                it.split(' ').size <=
                                    5 &&
                                    words(it).intersect(words).isNotEmpty()
                            },
                        )
                    note +=
                        "\n" +
                        if (labels.isEmpty()) {
                            wording.note("scroll_end", "direction" to action.direction.name.lowercase(), "edge" to edge(action.direction))
                        } else {
                            wording.note(
                                "scrolled",
                                "edge" to edge(action.direction),
                                "count" to labels.size,
                                "preview" to (
                                    labels.take(3).joinToString { text ->
                                        val shown =
                                            if (text.codePointCount(0, text.length) <= 30) {
                                                text
                                            } else {
                                                text.substring(0, text.offsetByCodePoints(0, 29)) + "…"
                                            }
                                        JsonPrimitive(shown).toString()
                                    } + if (labels.size > 3) ", …" else ""
                                ),
                                "direction" to action.direction.name.lowercase(),
                            )
                        }
                } else if (action != null && action !is Screenshot) {
                    lastScroll = null
                }
            }
            return result(TaskStatus.FAILED, if (step >= settings.maxSteps) "max_steps" else "max_time")
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            return result(if (isStopped) TaskStatus.CANCELLED else TaskStatus.FAILED, if (isStopped) "cancelled" else "timeout")
        } catch (e: Exception) {
            val cause = e.message?.takeIf { it.isNotBlank() }?.let { "${e.javaClass.simpleName}: $it" } ?: e.javaClass.simpleName
            return result(TaskStatus.FAILED, "worker_error: ${clip(cause, MAX_ERROR_CHARS)}")
        } finally {
            model.close()
        }
    }

    private fun action(
        call: WorkerCall,
        observation: Observation,
        revision: Long,
    ): Action {
        require(call.name in WorkerSchemas.schemas && call.name !in setOf("finish", "ask_user"))
        val fields =
            call.arguments.toMutableMap().apply {
                remove("intent")
                if (call.name == "swipe") {
                    put(
                        "start",
                        buildJsonObject {
                            put("x", call.arguments.getValue("start_x"))
                            put("y", call.arguments.getValue("start_y"))
                        },
                    )
                    put(
                        "end",
                        buildJsonObject {
                            put("x", call.arguments.getValue("end_x"))
                            put("y", call.arguments.getValue("end_y"))
                        },
                    )
                    listOf("start_x", "start_y", "end_x", "end_y").forEach { remove(it) }
                    if (get("duration_ms") == JsonNull) remove("duration_ms")
                }
                put("kind", JsonPrimitive(call.name))
                put("action_id", JsonPrimitive(UUID.randomUUID().toString()))
                put("task_id", JsonPrimitive(taskId))
                put("task_revision", JsonPrimitive(revision))
                put("bound_observation_id", JsonPrimitive(observation.observationId))
            }
        return ProtocolJson.decodeFromJsonElement(Action.serializer(), JsonObject(fields))
    }

    private fun activeBackend() = (backend as? PreferredDeviceBackend)?.active

    private fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** What a call does, in words a person reading the conversation can follow. */
    private fun describe(
        name: String,
        args: JsonObject,
        o: Observation,
    ): String {
        fun quoted(value: String?) = JsonPrimitive(clip(value.orEmpty(), QUOTED_CHARS)).toString()

        fun target() = quoted(label(o, args["element"]?.jsonPrimitive?.intOrNull))

        fun point(prefix: String) = "(${args[prefix + "x"]?.jsonPrimitive?.content}, ${args[prefix + "y"]?.jsonPrimitive?.content})"
        return when (name) {
            "observe" -> {
                "Read the screen"
            }

            "launch_app" -> {
                "Open ${args.text("package")}" + (args.text("activity")?.let { " ($it)" } ?: "")
            }

            "activate_element" -> {
                "Tap ${target()}"
            }

            "long_press" -> {
                "Long-press ${target()}"
            }

            "set_text" -> {
                (if ((args["replace"] as? JsonPrimitive)?.content == "false") "Append " else "Enter ") +
                    "${quoted(args.text("text"))} in ${target()}"
            }

            "scroll" -> {
                "Scroll ${args.text("direction")}" +
                    if (args["element"]?.jsonPrimitive?.intOrNull != null) " in ${target()}" else ""
            }

            "back" -> {
                "Press Back"
            }

            "home" -> {
                "Go Home"
            }

            "tap_point" -> {
                "Tap at ${point("")}"
            }

            "swipe" -> {
                "Swipe from ${point("start_")} to ${point("end_")}"
            }

            "screenshot" -> {
                "Take a screenshot"
            }

            "ime_action" -> {
                "Press ${args.text("action")} in ${target()}"
            }

            "open_url" -> {
                "Open ${args.text("url")}"
            }

            "open_notifications" -> {
                "Open notifications"
            }

            "ask_user" -> {
                "Ask: ${args.text("question")}"
            }

            "finish" -> {
                "Finish: ${args.text("summary")}"
            }

            else -> {
                name
            }
        }
    }

    private fun signature(o: Observation) = "${o.packageName}/${o.activity}:${o.elements}"

    private fun identity(e: Element) = listOf(e.role, e.text, e.contentDescription, e.resourceId, e.checked, e.selected)

    private fun elementLabel(element: Element): String =
        when {
            element.password -> "<password>"
            !element.text.isNullOrEmpty() -> element.text
            !element.contentDescription.isNullOrEmpty() -> element.contentDescription
            !element.resourceId.isNullOrEmpty() -> element.resourceId.substringAfterLast(":id/")
            else -> element.role.name.lowercase()
        }

    private fun label(
        o: Observation,
        index: Int?,
    ) = o.elements.getOrNull(index ?: -1)?.let(::elementLabel) ?: "#$index"

    private fun words(text: String) =
        Regex("[a-z0-9]+")
            .findAll(text.lowercase())
            .map { it.value }
            .filter { it !in FILLER }
            .toSet()

    private fun edge(d: ScrollDirection) =
        when (d) {
            ScrollDirection.DOWN -> "bottom"
            ScrollDirection.UP -> "top"
            ScrollDirection.LEFT -> "left"
            ScrollDirection.RIGHT -> "right"
        }

    private fun opposite(d: ScrollDirection) =
        when (d) {
            ScrollDirection.DOWN -> ScrollDirection.UP
            ScrollDirection.UP -> ScrollDirection.DOWN
            ScrollDirection.LEFT -> ScrollDirection.RIGHT
            ScrollDirection.RIGHT -> ScrollDirection.LEFT
        }

    private fun boundedSummary(summary: String) =
        if (summary.length <=
            MAX_SUMMARY
        ) {
            summary
        } else {
            summary.take(MAX_SUMMARY) + " " + wording.note("summary_cut", "count" to MAX_SUMMARY)
        }

    private data class ScrollState(
        val direction: ScrollDirection,
        val target: String,
        val productive: Boolean,
        val found: Boolean,
    )

    companion object {
        private const val MAX_ERROR_CHARS = 200
        private const val MAX_REPLAYED_ARGS = 800
        private const val MAX_QUESTION = 500
        const val MAX_SUMMARY = 1000

        private fun clip(
            value: String,
            max: Int,
        ) = if (value.length <= max) value else value.take(max - 1) + "…"

        private const val QUOTED_CHARS = 120
        private val FILLER =
            "the and you your for with was are has have this that from not but its will been is on in at of to a an it i my me what when where who which how does did time tell find according page open chrome app screen phone please can"
                .split(
                    ' ',
                ).toSet()
    }
}

data class WorkerStep(
    val step: Int,
    val revision: Long,
    val kind: String,
    val result: String,
    val timing: StepTiming,
    val callId: String? = null,
    val responseId: String? = null,
    val outputItemId: String? = null,
    /** The call in words, with password text redacted. */
    val detail: String = kind,
    /** Why the worker said it made the call. */
    val intent: String? = null,
    /** The backend serving the task when the step ran, once one has read the screen. */
    val backend: String? = null,
)
