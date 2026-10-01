package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.devicecontrol.portal.center
import com.colonelpanic.eva.devicecontrol.proto.Action
import com.colonelpanic.eva.devicecontrol.proto.ActionResult
import com.colonelpanic.eva.devicecontrol.proto.ActivateElement
import com.colonelpanic.eva.devicecontrol.proto.AppNotFound
import com.colonelpanic.eva.devicecontrol.proto.Back
import com.colonelpanic.eva.devicecontrol.proto.BackendUnavailable
import com.colonelpanic.eva.devicecontrol.proto.Element
import com.colonelpanic.eva.devicecontrol.proto.ElementNotFound
import com.colonelpanic.eva.devicecontrol.proto.ErrorInfo
import com.colonelpanic.eva.devicecontrol.proto.ExecutionStatus
import com.colonelpanic.eva.devicecontrol.proto.Home
import com.colonelpanic.eva.devicecontrol.proto.ImeAction
import com.colonelpanic.eva.devicecontrol.proto.ImeActionName
import com.colonelpanic.eva.devicecontrol.proto.NotActionable
import com.colonelpanic.eva.devicecontrol.proto.Observation
import com.colonelpanic.eva.devicecontrol.proto.OpenNotifications
import com.colonelpanic.eva.devicecontrol.proto.ProtectedContent
import com.colonelpanic.eva.devicecontrol.proto.Scroll
import com.colonelpanic.eva.devicecontrol.proto.ScrollDirection
import com.colonelpanic.eva.devicecontrol.proto.SetText
import com.colonelpanic.eva.devicecontrol.proto.SetTextDetails
import com.colonelpanic.eva.devicecontrol.proto.StaleObservation
import com.colonelpanic.eva.devicecontrol.proto.TapPoint
import com.colonelpanic.eva.devicecontrol.proto.TextMismatch
import com.colonelpanic.eva.devicecontrol.proto.Timeout
import com.colonelpanic.eva.devicecontrol.proto.renderCompact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Screen tools the conversation model calls itself, one input per invocation, over the same
 * [DeviceBackend] device tasks use. Inputs name an element from an observation EVA recorded; the
 * backend rechecks that element before delivering anything. Callers hold the device lease.
 */
class ScreenActions(
    private val enabled: () -> Boolean,
    private val select: suspend () -> Choice,
    private val elapsedMillis: () -> Long,
) {
    /** The backends to use now: [key] identifies their configuration, so a change starts a new session. */
    sealed interface Choice {
        /** Backends in preference order; one that is not ready or cannot read the screen hands over to the next. */
        data class Ready(
            val key: String,
            val routes: List<Route>,
        ) : Choice {
            constructor(key: String, create: () -> DeviceBackend) : this(key, listOf(Route(key, { null }, create)))
        }

        data class Unavailable(
            val reason: String,
        ) : Choice
    }

    class Route(
        val name: String,
        val problem: suspend () -> String?,
        val create: () -> DeviceBackend,
    )

    enum class Operation { OBSERVE, TAP, SET_TEXT, SCROLL, NAVIGATE, PRESS_ENTER }

    private class Recorded(
        val observation: Observation,
        val capturedAt: Long,
        val backend: DeviceBackend,
    )

    private class Session(
        val key: String,
        val routes: List<Route>,
    ) {
        val refs = LinkedHashMap<String, Recorded>()
        val opened = mutableMapOf<String, DeviceBackend>()
    }

    /** A screen read and the backend that read it, which any input bound to it must use. */
    private class Read(
        val observation: Observation,
        val backend: DeviceBackend,
    )

    private val lock = Mutex()
    private var session: Session? = null

    /**
     * References never repeat: the counter spans sessions, and the namespace differs per process, so
     * a reference kept in conversation history cannot name a screen recorded after a restart.
     */
    private val namespace = UUID.randomUUID().toString().replace("-", "")
    private var issued = 0

    /** Forgets recorded screens, so references issued before something else drove the device are refused. */
    fun reset() {
        synchronized(this) { session = null }
    }

    fun backend(operation: Operation): ExecutionBackend =
        object : ExecutionBackend {
            override fun usesDeviceUi(proposal: ToolProposal): Boolean = true

            override suspend fun unavailableReason(): String? {
                if (!enabled()) return DISABLED
                return when (val choice = select()) {
                    is Choice.Unavailable -> {
                        choice.reason
                    }

                    is Choice.Ready -> {
                        val problems = choice.routes.map { "${it.name}: ${it.problem() ?: return null}" }
                        "No screen control backend is ready. ${problems.joinToString(" ")}"
                    }
                }
            }

            override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome =
                run(operation, arguments, UUID.randomUUID().toString())

            override suspend fun execute(proposal: ToolProposal): ExecutionOutcome = run(operation, proposal.arguments, proposal.callId)
        }

    private suspend fun run(
        operation: Operation,
        arguments: Map<String, String>,
        callId: String,
    ): ExecutionOutcome =
        lock.withLock {
            if (!enabled()) return@withLock ExecutionOutcome(InvocationStatus.NOT_EXECUTED, DISABLED)
            val current =
                when (val choice = select()) {
                    is Choice.Unavailable -> {
                        return@withLock ExecutionOutcome(InvocationStatus.NOT_EXECUTED, choice.reason)
                    }

                    is Choice.Ready -> {
                        synchronized(this) {
                            session?.takeIf { it.key == choice.key } ?: Session(choice.key, choice.routes).also { session = it }
                        }
                    }
                }
            try {
                when (operation) {
                    Operation.OBSERVE -> observe(current)
                    Operation.NAVIGATE -> navigate(current, arguments, callId)
                    else -> targeted(current, operation, arguments, callId)
                }
            } catch (failure: Unreadable) {
                ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "$UNREADABLE ${failure.message}")
            } catch (_: ObservationFailure) {
                ExecutionOutcome(InvocationStatus.NOT_EXECUTED, UNREADABLE)
            }
        }

    private class Unreadable(
        message: String,
    ) : Exception(message)

    /**
     * Reads the screen through the first backend that can, before any input of this call is sent,
     * so a backend that passes its check but cannot read hands over like it does for device tasks.
     */
    private suspend fun read(session: Session): Read {
        val failures = mutableListOf<String>()
        for (route in session.routes) {
            val problem = route.problem()
            if (problem != null) {
                failures += "${route.name}: $problem"
                continue
            }
            val backend = synchronized(this) { session.opened.getOrPut(route.name) { route.create() } }
            try {
                return Read(backend.observe(), backend)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (session.routes.size == 1 && error is ObservationFailure) throw error
                failures += "${route.name}: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        throw Unreadable(failures.joinToString(" "))
    }

    private suspend fun observe(session: Session): ExecutionOutcome {
        val read = read(session)
        return ExecutionOutcome(InvocationStatus.COMPLETED, project(session, read))
    }

    private suspend fun navigate(
        session: Session,
        arguments: Map<String, String>,
        callId: String,
    ): ExecutionOutcome {
        // A global button needs no element, so it binds to a screen read here rather than one the model saw.
        val read = read(session)
        val bound = read.observation.observationId
        val action =
            when (arguments["button"]) {
                "back" -> Back(actionId(callId), TASK, 0, bound)
                "home" -> Home(actionId(callId), TASK, 0, bound)
                "notifications" -> OpenNotifications(actionId(callId), TASK, 0, bound)
                else -> return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "Choose back, home, or notifications.")
            }
        val label =
            when (action) {
                is Back -> "Pressed Back."
                is Home -> "Went to the home screen."
                else -> "Opened the notification shade."
            }
        return outcome(session, read.backend, read.backend.perform(action), label)
    }

    private suspend fun targeted(
        session: Session,
        operation: Operation,
        arguments: Map<String, String>,
        callId: String,
    ): ExecutionOutcome {
        val recorded =
            consume(session, arguments["observationRef"].orEmpty())
                ?: return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, UNUSABLE_REFERENCE)
        val observation = recorded.observation
        val bound = observation.observationId
        val node = arguments["node"]?.toIntOrNull()
        val element = node?.let(observation.elements::getOrNull)
        if (operation != Operation.SCROLL && element == null) return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, NO_ELEMENT)
        if (operation == Operation.SCROLL && node != null && element == null) {
            return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, NO_ELEMENT)
        }
        val id = actionId(callId)
        val (action, label) =
            when (operation) {
                Operation.TAP -> {
                    val target = checkNotNull(element)
                    tap(observation, target, id) to "Tapped ${describe(target)}."
                }

                Operation.SET_TEXT -> {
                    val target = checkNotNull(element)
                    if (!target.editable) {
                        return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "[${target.index}] ${role(target)} is not a text field.")
                    }
                    SetText(id, TASK, 0, bound, target.index, arguments["text"].orEmpty()) to "Replaced the text in ${describe(target)}."
                }

                Operation.PRESS_ENTER -> {
                    val target = checkNotNull(element)
                    ImeAction(id, TASK, 0, bound, ImeActionName.ENTER, target.index) to "Pressed Enter in ${describe(target)}."
                }

                Operation.SCROLL -> {
                    val direction =
                        ScrollDirection.entries.firstOrNull { it.name.equals(arguments["direction"], ignoreCase = true) }
                            ?: return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "Choose up, down, left, or right.")
                    // An element index makes the backend recheck the screen; a bare scroll would not.
                    val target = element ?: mainScrollable(observation)
                    if (target == null) {
                        val now = recorded.backend.observe()
                        if (fingerprint(now) != fingerprint(observation)) {
                            return ExecutionOutcome(
                                InvocationStatus.NOT_EXECUTED,
                                "Nothing was sent: the screen changed since that observation.\n" +
                                    project(session, Read(now, recorded.backend)),
                            )
                        }
                        Scroll(id, TASK, 0, now.observationId, direction) to "Scrolled the screen ${direction.name.lowercase()}."
                    } else {
                        Scroll(id, TASK, 0, bound, direction, target.index) to
                            "Scrolled ${describe(target)} ${direction.name.lowercase()}."
                    }
                }

                else -> {
                    error("Unexpected $operation")
                }
            }
        return outcome(session, recorded.backend, recorded.backend.perform(action), label)
    }

    /**
     * A clickable element is activated; anything else is touched at its centre, so a label inside a
     * clickable row presses the row while the backend still rechecks the label that was named.
     */
    private fun tap(
        observation: Observation,
        target: Element,
        id: String,
    ): Action {
        val bound = observation.observationId
        if (target.clickable || target.checkable) return ActivateElement(id, TASK, 0, bound, target.index)
        val center = target.bounds.center()
        return TapPoint(id, TASK, 0, bound, center.x, center.y, target.index)
    }

    private fun fingerprint(observation: Observation) =
        listOf(observation.packageName, observation.activity) +
            observation.elements.map { listOf(it.role, it.text, it.contentDescription, it.resourceId, it.bounds) }

    /** The element the backend would scroll by default: the largest scrollable one. */
    private fun mainScrollable(observation: Observation): Element? =
        observation.elements
            .filter { it.scrollable && it.bounds.right > it.bounds.left }
            .maxWithOrNull(
                compareBy<Element> {
                    (it.bounds.right - it.bounds.left).toLong() * (it.bounds.bottom - it.bounds.top)
                }.thenByDescending { it.index },
            )

    private fun outcome(
        session: Session,
        backend: DeviceBackend,
        result: ActionResult,
        label: String,
    ): ExecutionOutcome {
        val after =
            result.observation?.let { project(session, Read(it, backend)) }
                ?: "EVA could not read the screen afterwards; look at it again before continuing."
        val unsettled = if (result.unsettled) " The screen was still changing; look again before relying on it." else ""
        val readBack = (result.details as? SetTextDetails)?.takeIf { !it.verified }?.let(::mismatch)
        return when (result.executionStatus) {
            ExecutionStatus.NOT_DISPATCHED -> {
                ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "Nothing was sent: ${explain(result.error)}\n$after")
            }

            ExecutionStatus.OUTCOME_UNKNOWN, ExecutionStatus.IN_FLIGHT -> {
                ExecutionOutcome(
                    InvocationStatus.UNKNOWN,
                    "EVA cannot tell whether the input took effect: ${explain(result.error)} Check the screen before trying again.\n$after",
                )
            }

            ExecutionStatus.EXECUTED -> {
                if (result.ok) {
                    ExecutionOutcome(InvocationStatus.COMPLETED, "$label $DELIVERY_NOTE$unsettled\n$after")
                } else {
                    ExecutionOutcome(
                        InvocationStatus.FAILED,
                        "The input was delivered but did not do what was asked: ${readBack ?: explain(result.error)}$unsettled\n$after",
                    )
                }
            }
        }
    }

    private fun project(
        session: Session,
        read: Read,
    ): String {
        val reference = "screen-$namespace-${++issued}"
        session.refs[reference] = Recorded(read.observation, elapsedMillis(), read.backend)
        while (session.refs.size > MAX_REFERENCES) session.refs.remove(session.refs.keys.first())
        return read.observation.renderCompact(reference)
    }

    /** Reserves a recorded screen for one input, even when the backend then refuses that input. */
    private fun consume(
        session: Session,
        reference: String,
    ): Recorded? {
        val recorded = session.refs.remove(reference) ?: return null
        return recorded.takeIf { elapsedMillis() - it.capturedAt <= LIFETIME_MILLIS }
    }

    private fun explain(error: ErrorInfo?): String =
        when (error) {
            null -> "the device reported no detail."
            is StaleObservation -> "the screen changed since that observation. Look at it again."
            is ElementNotFound -> NO_ELEMENT
            is NotActionable -> "element [${error.element}] cannot take that input (${error.reason.name.lowercase().replace('_', ' ')})."
            is TextMismatch -> "the field did not end up with the requested text."
            is ProtectedContent -> "the screen is protected and cannot be read or controlled."
            is AppNotFound -> "the expected app did not come to the front."
            is BackendUnavailable -> "the screen-control backend is unavailable."
            is Timeout -> "the device did not respond in time."
            else -> "the device refused it (${error.javaClass.simpleName})."
        }

    private fun mismatch(details: SetTextDetails): String {
        val expected = details.expected ?: return "the password field could not be confirmed."
        return "the field reads ${quote(details.actual.orEmpty())} instead of ${quote(expected)}."
    }

    /** Quotes on-screen text, saying so when only its start fits. */
    private fun quote(text: String): String {
        if (text.length <= QUOTE_CHARS) {
            return kotlinx.serialization.json
                .JsonPrimitive(text)
                .toString()
        }
        val start =
            kotlinx.serialization.json
                .JsonPrimitive(text.take(QUOTE_CHARS) + "…")
                .toString()
        return "$start (first $QUOTE_CHARS of ${text.length} characters)"
    }

    private fun describe(element: Element): String {
        val label = if (element.password) null else element.text?.takeIf(String::isNotBlank) ?: element.contentDescription
        return "[${element.index}] ${role(element)}" + label?.takeIf(String::isNotBlank)?.let { " ${quote(it)}" }.orEmpty()
    }

    private fun role(element: Element) = element.role.name.lowercase()

    private fun actionId(callId: String) = "eva-direct-$callId"

    companion object {
        private const val TASK = "eva-direct"
        private const val QUOTE_CHARS = 200
        const val MAX_REFERENCES = 8
        const val LIFETIME_MILLIS = 180_000L
        const val DISABLED = "Screen control is switched off in EVA's settings."
        const val UNREADABLE = "EVA could not read the screen. Nothing was sent."
        const val NO_ELEMENT = "That element number is not in the screen EVA recorded. Look at the screen again."
        const val UNUSABLE_REFERENCE =
            "That screen observation is not usable: each one serves a single input, expires after three minutes, and is " +
                "dropped when a device task runs. Look at the screen again first."
        const val DELIVERY_NOTE = "Input was delivered; the screen below is what followed, not proof the task is done."
    }
}
