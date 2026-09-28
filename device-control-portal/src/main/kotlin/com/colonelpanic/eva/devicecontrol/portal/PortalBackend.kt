package com.colonelpanic.eva.devicecontrol.portal

import com.colonelpanic.eva.devicecontrol.DeviceBackend
import com.colonelpanic.eva.devicecontrol.ObservationFailure
import com.colonelpanic.eva.devicecontrol.proto.Action
import com.colonelpanic.eva.devicecontrol.proto.ActionDetails
import com.colonelpanic.eva.devicecontrol.proto.ActionResult
import com.colonelpanic.eva.devicecontrol.proto.ActivateElement
import com.colonelpanic.eva.devicecontrol.proto.AppNotFound
import com.colonelpanic.eva.devicecontrol.proto.BackendUnavailable
import com.colonelpanic.eva.devicecontrol.proto.DuplicateAction
import com.colonelpanic.eva.devicecontrol.proto.Element
import com.colonelpanic.eva.devicecontrol.proto.ElementNotFound
import com.colonelpanic.eva.devicecontrol.proto.ErrorInfo
import com.colonelpanic.eva.devicecontrol.proto.ExecutionStatus
import com.colonelpanic.eva.devicecontrol.proto.ImeAction
import com.colonelpanic.eva.devicecontrol.proto.ImeActionDetails
import com.colonelpanic.eva.devicecontrol.proto.LaunchApp
import com.colonelpanic.eva.devicecontrol.proto.LongPress
import com.colonelpanic.eva.devicecontrol.proto.NotActionable
import com.colonelpanic.eva.devicecontrol.proto.NotActionableReason
import com.colonelpanic.eva.devicecontrol.proto.Observation
import com.colonelpanic.eva.devicecontrol.proto.OpenUrl
import com.colonelpanic.eva.devicecontrol.proto.ProtectedContent
import com.colonelpanic.eva.devicecontrol.proto.ProtectedReason
import com.colonelpanic.eva.devicecontrol.proto.Screen
import com.colonelpanic.eva.devicecontrol.proto.ScreenshotDetails
import com.colonelpanic.eva.devicecontrol.proto.Scroll
import com.colonelpanic.eva.devicecontrol.proto.SetText
import com.colonelpanic.eva.devicecontrol.proto.SetTextDetails
import com.colonelpanic.eva.devicecontrol.proto.StaleObservation
import com.colonelpanic.eva.devicecontrol.proto.Swipe
import com.colonelpanic.eva.devicecontrol.proto.TapPoint
import com.colonelpanic.eva.devicecontrol.proto.TextMismatch
import com.colonelpanic.eva.devicecontrol.proto.UnavailableReason
import com.colonelpanic.eva.devicecontrol.proto.Unsupported
import com.colonelpanic.eva.devicecontrol.proto.kind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Matches the prototype's polling/quiet-window policy, including slow launches and cached reads. */
data class SettlePolicy(
    val pollMillis: Long = 100,
    val gestureMarginMillis: Long = 100,
    val quietMillis: Long = 300,
    val changeGraceMillis: Long = 1500,
    val budgetMillis: Long = 4000,
    val launchBudgetMillis: Long = 6000,
    val readBudgetMillis: Long = 6000,
) {
    init {
        require(pollMillis > 0 && quietMillis >= 0 && gestureMarginMillis >= 0 && changeGraceMillis >= 0)
        require(budgetMillis > 0 && launchBudgetMillis > 0 && readBudgetMillis > 0)
    }
}

data class ActionTiming(
    val kind: String,
    val recheckMillis: Long,
    val httpMillis: Long,
    val settleMillis: Long,
    val polls: Int,
    val settled: Boolean,
)

class PortalBackend(
    private val client: PortalTransport,
    private val policy: SettlePolicy = SettlePolicy(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val timestamp: () -> String = { Instant.now().toString() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val timing: (ActionTiming) -> Unit = {},
    private val launchAliases: Map<String, List<String>> = com.colonelpanic.eva.devicecontrol.worker.DEFAULT_LAUNCH_ALIASES,
) : DeviceBackend {
    private val lock = Mutex()
    private val session = UUID.randomUUID().toString()
    private var sequence = 0L
    private var latest: MappedScreen? = null
    private var geometry: Screen? = null
    private val actionIds = LinkedHashSet<String>()

    override suspend fun observe(): Observation =
        lock.withLock {
            val start = clock()
            try {
                snapshot().also { latest = it }.observation.also {
                    timing(ActionTiming("observe", 0, clock() - start, 0, 1, true))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                throw ObservationFailure(BackendUnavailable(backend = "portal", executionStatus = ExecutionStatus.NOT_DISPATCHED))
            }
        }

    override suspend fun perform(action: Action): ActionResult =
        lock.withLock {
            val caller = currentCoroutineContext()
            val started = timestamp()
            val bound = latest

            fun refused(error: ErrorInfo) =
                ActionResult(
                    action.actionId,
                    action.kind,
                    false,
                    started,
                    timestamp(),
                    latest?.observation,
                    error = error,
                    executionStatus = ExecutionStatus.NOT_DISPATCHED,
                )
            if (!actionIds.add(action.actionId)) return@withLock refused(DuplicateAction(action.actionId, action.boundObservationId))
            if (actionIds.size > 1024) actionIds.remove(actionIds.first())
            if (bound == null || bound.observation.observationId != action.boundObservationId) {
                return@withLock refused(StaleObservation(action.actionId, action.boundObservationId, bound?.observation?.observationId))
            }
            if (!valid(action)) return@withLock refused(Unsupported(action.actionId, action.boundObservationId, action.kind, "portal"))
            var dispatched = false
            var recheckMillis = 0L
            var httpMillis = 0L
            var settleMillis = 0L
            var polls = 0
            var settled = true

            suspend fun send(command: PortalCommand) {
                caller.ensureActive()
                val begin = clock()
                dispatched = true
                try {
                    client.command(command)
                } finally {
                    httpMillis += clock() - begin
                }
            }

            suspend fun waitFor(
                before: MappedScreen,
                command: PortalCommand? = null,
                slow: Boolean = false,
                done: ((MappedScreen) -> Boolean)? = null,
            ): Settled {
                val result = settle(before, (command?.gestureMs ?: 0) + policy.gestureMarginMillis, slow, done)
                settleMillis += result.millis
                polls += result.polls
                settled = settled && result.settled
                return result
            }
            // Cancellation cannot recall an HTTP mutation. Retain the caller's lease until this bounded exchange settles.
            withContext(NonCancellable) {
                try {
                    val index = targetIndex(action)
                    var before = bound
                    if (index != null) {
                        val begin = clock()
                        before = snapshot().also { latest = it }
                        recheckMillis = clock() - begin
                        val old = bound.observation.elements.getOrNull(index)
                        val fresh = before.observation.elements.getOrNull(index)
                        if (old == null ||
                            fresh == null
                        ) {
                            return@withContext refused(ElementNotFound(action.actionId, action.boundObservationId, index))
                        }
                        if (bound.observation.packageName != before.observation.packageName || identity(old) != identity(fresh)) {
                            return@withContext refused(
                                StaleObservation(action.actionId, action.boundObservationId, before.observation.observationId),
                            )
                        }
                    }
                    caller.ensureActive()
                    val plan =
                        when (val planned = PrimitivePlanner.plan(action, before.observation, "portal")) {
                            is Planned.Refused -> return@withContext refused(planned.error)
                            is Planned.Ready -> planned.plan
                        }
                    var details: ActionDetails? = null
                    var failure: ErrorInfo? = null
                    var after: MappedScreen
                    when (plan) {
                        is Plan.Primitive -> {
                            var launchError = false
                            try {
                                send(plan.command)
                            } catch (error: PortalCommandFailure) {
                                if (action is LaunchApp) {
                                    if (listOf("not found", "not installed", "no such package", "unknown package").any {
                                            it in
                                                error.detail.lowercase()
                                        }
                                    ) {
                                        return@withContext refused(
                                            AppNotFound(action.actionId, action.boundObservationId, action.packageName),
                                        )
                                    }
                                    launchError = true
                                } else if (action is OpenUrl) {
                                    return@withContext refused(
                                        action.packageName?.let { AppNotFound(action.actionId, action.boundObservationId, it) }
                                            ?: BackendUnavailable(
                                                action.actionId,
                                                action.boundObservationId,
                                                "portal",
                                                ExecutionStatus.NOT_DISPATCHED,
                                            ),
                                    )
                                } else {
                                    throw error
                                }
                            }
                            after =
                                waitFor(
                                    before,
                                    plan.command,
                                    plan.slowStart,
                                    plan.foregroundPackage?.let { pkg ->
                                        { screen ->
                                            screen.observation.packageName == pkg ||
                                                (action is LaunchApp && screen.observation.packageName in launchAliases[pkg].orEmpty())
                                        }
                                    },
                                ).screen
                            if (action is ImeAction) details = ImeActionDetails(action.action)
                            val expected =
                                when (action) {
                                    is LaunchApp -> action.packageName
                                    is OpenUrl -> action.packageName
                                    else -> null
                                }
                            if (expected != null && after.observation.packageName != expected &&
                                !(action is LaunchApp && after.observation.packageName in launchAliases[expected].orEmpty())
                            ) {
                                failure =
                                    if (launchError) {
                                        BackendUnavailable(action.actionId, action.boundObservationId, "portal")
                                    } else {
                                        AppNotFound(action.actionId, action.boundObservationId, expected, ExecutionStatus.EXECUTED)
                                    }
                            }
                        }

                        is Plan.TypeText -> {
                            var screen = before
                            var field = plan.target.takeIf { it.focused }
                            if (field == null) {
                                val tap = PrimitivePlanner.tap(plan.target.bounds.center())
                                send(tap)
                                screen = waitFor(screen, tap, done = { focusedField(it.observation, plan.target) != null }).screen
                                field = focusedField(screen.observation, plan.target)
                            }
                            if (field == null) {
                                after = screen
                                failure =
                                    NotActionable(
                                        action.actionId,
                                        action.boundObservationId,
                                        plan.target.index,
                                        NotActionableReason.NOT_FOCUSED,
                                        ExecutionStatus.EXECUTED,
                                    )
                                details =
                                    SetTextDetails(plan.target.index, false, if (plan.target.password) null else plan.action.text, null)
                            } else {
                                val input = PrimitivePlanner.input(plan.action, fieldText(screen, field), field.password)
                                input.moveEnd?.let { send(it) }
                                try {
                                    send(input.input)
                                } catch (_: PortalCommandFailure) {
                                    // Read-back decides whether text landed.
                                }
                                after =
                                    waitFor(screen, done = { matches(readBack(it, plan.target), input.expected, field.password) }).screen
                                val actual = readBack(after, plan.target)
                                val verified = matches(actual, input.expected, field.password)
                                val redact = plan.target.password || field.password
                                val expectedShown = if (redact) null else input.expected
                                val actualShown = if (redact) null else actual
                                details = SetTextDetails(plan.target.index, verified, expectedShown, actualShown)
                                if (!verified) {
                                    failure =
                                        TextMismatch(
                                            action.actionId,
                                            action.boundObservationId,
                                            plan.target.index,
                                            expectedShown,
                                            actualShown,
                                        )
                                }
                            }
                        }

                        Plan.Capture -> {
                            val begin = clock()
                            val png = client.screenshot()
                            httpMillis += clock() - begin
                            val captureStart = clock()
                            after = snapshot().also { latest = it }
                            settleMillis += clock() - captureStart
                            polls++
                            val dimensions = ByteBuffer.wrap(png, 16, 8)
                            val width = dimensions.int
                            val height = dimensions.int
                            require(width > 0 && height > 0)
                            details =
                                ScreenshotDetails(after.observation.observationId, width, height, Base64.getEncoder().encodeToString(png))
                        }
                    }
                    ActionResult(
                        action.actionId,
                        action.kind,
                        failure == null,
                        started,
                        timestamp(),
                        after.observation,
                        details = details,
                        error = failure,
                        executionStatus = failure?.executionStatus ?: ExecutionStatus.EXECUTED,
                        unsettled = !settled,
                    )
                } catch (error: CaptureRejected) {
                    ActionResult(
                        action.actionId,
                        action.kind,
                        false,
                        started,
                        timestamp(),
                        latest?.observation,
                        error =
                            ProtectedContent(
                                action.actionId,
                                action.boundObservationId,
                                if (error.secure) ProtectedReason.SECURE_WINDOW else ProtectedReason.SCREENSHOT_REJECTED,
                            ),
                    )
                } catch (_: Exception) {
                    val status = if (dispatched) ExecutionStatus.OUTCOME_UNKNOWN else ExecutionStatus.NOT_DISPATCHED
                    val after = runCatching { snapshot().also { latest = it }.observation }.getOrNull()
                    ActionResult(
                        action.actionId,
                        action.kind,
                        false,
                        started,
                        timestamp(),
                        after,
                        error = BackendUnavailable(action.actionId, action.boundObservationId, "portal", status),
                        executionStatus = status,
                        postObservationFailure = if (after == null) "Portal could not read the screen." else null,
                    )
                } finally {
                    timing(ActionTiming(action.kind.name.lowercase(), recheckMillis, httpMillis, settleMillis, polls, settled))
                }
            }
        }

    private data class Settled(
        val screen: MappedScreen,
        val millis: Long,
        val polls: Int,
        val settled: Boolean,
    )

    private suspend fun settle(
        before: MappedScreen,
        minWait: Long,
        slow: Boolean,
        done: ((MappedScreen) -> Boolean)?,
    ): Settled {
        val start = clock()
        sleep(minWait)
        val baseline = signature(before.observation)
        var previous: List<Any?>? = null
        var unchangedSince = start
        var polls = 0
        while (true) {
            val current = snapshot().also { latest = it }
            polls++
            val now = clock()
            val signature = signature(current.observation)
            if (signature != previous) unchangedSince = now
            val ready = done?.invoke(current) ?: (signature != baseline || now - start >= policy.changeGraceMillis)
            if (ready && previous != null && signature == previous && now - unchangedSince >= policy.quietMillis) {
                return Settled(current, now - start, polls, true)
            }
            if (now - start >=
                if (slow) policy.launchBudgetMillis else policy.budgetMillis
            ) {
                return Settled(current, now - start, polls, false)
            }
            previous = signature
            sleep(policy.pollMillis)
        }
    }

    private suspend fun snapshot(): MappedScreen {
        val start = clock()
        val id = "portal-$session-${++sequence}"
        while (true) {
            try {
                return PortalScreenMapper.map(client.state(), id, timestamp(), "portal", sequence).also { geometry = it.observation.screen }
            } catch (error: DegradedSnapshot) {
                if (clock() - start >= policy.readBudgetMillis) throw error
                sleep(policy.pollMillis)
            } catch (error: NoActiveWindow) {
                val screen = geometry ?: throw error
                return MappedScreen(
                    Observation(
                        id,
                        timestamp(),
                        "portal",
                        screen = screen,
                        sequence = sequence,
                        contentUnavailable = true,
                        unavailableReason = UnavailableReason.A11Y_UNAVAILABLE,
                    ),
                )
            }
        }
    }

    private fun valid(action: Action): Boolean =
        when (action) {
            is Swipe -> action.durationMs in 50..2000
            is LongPress -> action.durationMs == null || action.durationMs in 400..2000
            is SetText -> action.text.length <= 10_000
            is LaunchApp -> Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(action.packageName)
            else -> true
        }

    private fun targetIndex(action: Action): Int? =
        when (action) {
            is ActivateElement -> action.element
            is SetText -> action.element
            is LongPress -> action.element
            is ImeAction -> action.element
            is Scroll -> action.element
            is TapPoint -> action.within
            is Swipe -> action.within
            else -> null
        }

    private fun identity(element: Element) =
        listOf(element.role, element.text, element.contentDescription, element.resourceId, element.bounds)

    private fun signature(observation: Observation): List<Any?> =
        with(observation) {
            listOf(packageName, activity, keyboardShown, contentUnavailable, elements)
        }

    private fun sameField(
        candidate: Element,
        target: Element,
    ): Boolean =
        candidate.resourceId == target.resourceId &&
            candidate.bounds.left < target.bounds.right && target.bounds.left < candidate.bounds.right &&
            candidate.bounds.top < target.bounds.bottom && target.bounds.top < candidate.bounds.bottom

    private fun focusedField(
        screen: Observation,
        target: Element,
    ) = screen.elements
        .firstOrNull {
            it.focused && it.editable
        }?.takeIf { sameField(it, target) }

    private fun fieldText(
        screen: MappedScreen,
        field: Element,
    ) = if (field.password) screen.passwordTexts[field.index].orEmpty() else field.text.orEmpty()

    private fun readBack(
        screen: MappedScreen,
        target: Element,
    ): String? {
        val field =
            focusedField(screen.observation, target)
                ?: screen.observation.elements
                    .getOrNull(target.index)
                    ?.takeIf { it.editable && sameField(it, target) }
        return field?.let { fieldText(screen, it) }
    }

    private fun matches(
        actual: String?,
        expected: String,
        password: Boolean,
    ): Boolean =
        actual != null &&
            (
                actual == expected || (
                    password && actual.isNotEmpty() && actual.all { it in "•*●" } &&
                        (actual.length == expected.length || actual.length == expected.codePointCount(0, expected.length))
                )
            )
}
