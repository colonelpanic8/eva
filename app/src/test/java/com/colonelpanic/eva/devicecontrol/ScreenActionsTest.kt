package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.devicecontrol.ScreenActions.Choice
import com.colonelpanic.eva.devicecontrol.ScreenActions.Operation
import com.colonelpanic.eva.devicecontrol.proto.Action
import com.colonelpanic.eva.devicecontrol.proto.ActionResult
import com.colonelpanic.eva.devicecontrol.proto.ActivateElement
import com.colonelpanic.eva.devicecontrol.proto.Back
import com.colonelpanic.eva.devicecontrol.proto.BackendUnavailable
import com.colonelpanic.eva.devicecontrol.proto.Bounds
import com.colonelpanic.eva.devicecontrol.proto.Element
import com.colonelpanic.eva.devicecontrol.proto.ExecutionStatus
import com.colonelpanic.eva.devicecontrol.proto.Observation
import com.colonelpanic.eva.devicecontrol.proto.Orientation
import com.colonelpanic.eva.devicecontrol.proto.Role
import com.colonelpanic.eva.devicecontrol.proto.Screen
import com.colonelpanic.eva.devicecontrol.proto.Scroll
import com.colonelpanic.eva.devicecontrol.proto.SetText
import com.colonelpanic.eva.devicecontrol.proto.SetTextDetails
import com.colonelpanic.eva.devicecontrol.proto.StaleObservation
import com.colonelpanic.eva.devicecontrol.proto.TapPoint
import com.colonelpanic.eva.devicecontrol.proto.TextMismatch
import com.colonelpanic.eva.devicecontrol.proto.kind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenActionsTest {
    private class Phone : DeviceBackend {
        var sequence = 0
        var packageName = "com.example"
        var scrollable = false
        val performed = mutableListOf<Action>()
        var next: (Action, Observation) -> ActionResult = { action, after -> result(action, after) }

        fun screen() =
            Observation(
                "obs-${++sequence}",
                "2026-01-01T00:00:00Z",
                "fake",
                packageName = packageName,
                screen = Screen(1080, 1920, Orientation.PORTRAIT),
                elements =
                    listOf(
                        Element(0, Role.OTHER, bounds = Bounds(0, 0, 1080, 1920), scrollable = scrollable, depth = 0),
                        Element(1, Role.OTHER, bounds = Bounds(0, 100, 1080, 200), clickable = true, depth = 1, parentIndex = 0),
                        Element(2, Role.TEXT, "Battery", bounds = Bounds(40, 120, 400, 180), depth = 2, parentIndex = 1),
                        Element(3, Role.EDIT_TEXT, "", bounds = Bounds(0, 300, 1080, 400), editable = true, depth = 1, parentIndex = 0),
                        Element(4, Role.TEXT, "Header", bounds = Bounds(0, 0, 1080, 90), depth = 1, parentIndex = 0),
                    ),
            )

        override suspend fun observe() = screen()

        override suspend fun perform(action: Action): ActionResult {
            performed += action
            return next(action, screen())
        }
    }

    private var now = 0L
    private val phone = Phone()
    private var choice: Choice = Choice.Ready("portal") { phone }
    private var enabled = true
    private val actions = ScreenActions({ enabled }, { choice }, { now })

    private fun call(
        operation: Operation,
        vararg arguments: Pair<String, String>,
    ) = runBlocking { actions.backend(operation).execute(arguments.toMap()) }

    private fun reference(text: String) = Regex("observation (screen-[\\w-]+)").find(text)!!.groupValues[1]

    @Test
    fun `a completed tap activates a clickable element and returns the next screen`() {
        val ref = reference(call(Operation.OBSERVE).message)

        val outcome = call(Operation.TAP, "observationRef" to ref, "node" to "1")

        assertEquals(InvocationStatus.COMPLETED, outcome.status)
        assertEquals(1, (phone.performed.single() as ActivateElement).element)
        assertTrue(reference(outcome.message) != ref)
    }

    @Test
    fun `a label inside a clickable row is touched where it is so the label itself is rechecked`() {
        val ref = reference(call(Operation.OBSERVE).message)

        val outcome = call(Operation.TAP, "observationRef" to ref, "node" to "2")

        val tap = phone.performed.single() as TapPoint
        assertEquals(listOf(220, 150, 2), listOf(tap.x, tap.y, tap.within))
        assertTrue(outcome.message.startsWith("Tapped [2] text \"Battery\"."))
    }

    @Test
    fun `a scroll without an element binds to the main scrollable one`() {
        phone.scrollable = true
        val ref = reference(call(Operation.OBSERVE).message)

        call(Operation.SCROLL, "observationRef" to ref, "direction" to "down")

        val scroll = phone.performed.single() as Scroll
        assertEquals(listOf<Any?>(0, "obs-1"), listOf(scroll.element, scroll.boundObservationId))
    }

    @Test
    fun `a scroll with nothing scrollable refuses when a different app has come to the front`() {
        val ref = reference(call(Operation.OBSERVE).message)
        phone.packageName = "com.other"

        val outcome = call(Operation.SCROLL, "observationRef" to ref, "direction" to "down")

        assertEquals(InvocationStatus.NOT_EXECUTED, outcome.status)
        assertTrue(phone.performed.isEmpty())
    }

    @Test
    fun `references from another instance never resolve`() {
        val ref = reference(call(Operation.OBSERVE).message)
        val restarted = ScreenActions({ true }, { choice }, { now })
        runBlocking { restarted.backend(Operation.OBSERVE).execute(emptyMap()) }

        val outcome = runBlocking { restarted.backend(Operation.TAP).execute(mapOf("observationRef" to ref, "node" to "1")) }

        assertEquals(ScreenActions.UNUSABLE_REFERENCE, outcome.message)
        assertTrue(phone.performed.isEmpty())
    }

    @Test
    fun `delivered input that did not take effect is a failure, not a completion`() {
        phone.next = { action, after ->
            result(action, after, ok = false, error = TextMismatch(action.actionId, null, 3, "hi", "h"))
                .copy(details = SetTextDetails(3, false, "hi", "h"))
        }
        val ref = reference(call(Operation.OBSERVE).message)

        val outcome = call(Operation.SET_TEXT, "observationRef" to ref, "node" to "3", "text" to "hi")

        assertEquals(InvocationStatus.FAILED, outcome.status)
        assertTrue(outcome.message.contains("the field reads \"h\" instead of \"hi\""))
        assertTrue((phone.performed.single() as SetText).replace)
    }

    @Test
    fun `refused and uncertain inputs keep their distinct outcomes`() {
        phone.next = { action, after ->
            result(action, after, ok = false, error = StaleObservation(action.actionId), status = ExecutionStatus.NOT_DISPATCHED)
        }
        var ref = reference(call(Operation.OBSERVE).message)
        assertEquals(InvocationStatus.NOT_EXECUTED, call(Operation.TAP, "observationRef" to ref, "node" to "1").status)

        phone.next = { action, after ->
            result(
                action,
                after,
                ok = false,
                error = BackendUnavailable(action.actionId, backend = "fake"),
                status = ExecutionStatus.OUTCOME_UNKNOWN,
            )
        }
        ref = reference(call(Operation.OBSERVE).message)
        assertEquals(InvocationStatus.UNKNOWN, call(Operation.TAP, "observationRef" to ref, "node" to "1").status)
    }

    @Test
    fun `a reference serves one input and expires`() {
        val ref = reference(call(Operation.OBSERVE).message)
        call(Operation.TAP, "observationRef" to ref, "node" to "1")

        val reused = call(Operation.TAP, "observationRef" to ref, "node" to "1")
        assertEquals(InvocationStatus.NOT_EXECUTED, reused.status)
        assertEquals(ScreenActions.UNUSABLE_REFERENCE, reused.message)

        val old = reference(call(Operation.OBSERVE).message)
        now += ScreenActions.LIFETIME_MILLIS + 1
        assertEquals(InvocationStatus.NOT_EXECUTED, call(Operation.TAP, "observationRef" to old, "node" to "1").status)
        assertEquals(1, phone.performed.size)
    }

    @Test
    fun `references do not survive a device task or a backend change`() {
        val first = reference(call(Operation.OBSERVE).message)
        actions.reset()
        assertEquals(InvocationStatus.NOT_EXECUTED, call(Operation.TAP, "observationRef" to first, "node" to "1").status)

        val second = reference(call(Operation.OBSERVE).message)
        choice = Choice.Ready("shizuku") { phone }
        assertEquals(InvocationStatus.NOT_EXECUTED, call(Operation.TAP, "observationRef" to second, "node" to "1").status)
        assertTrue(phone.performed.isEmpty())
    }

    @Test
    fun `navigation binds to a fresh screen without a reference`() {
        val outcome = call(Operation.NAVIGATE, "button" to "back")

        assertEquals(InvocationStatus.COMPLETED, outcome.status)
        assertEquals("obs-1", (phone.performed.single() as Back).boundObservationId)
    }

    @Test
    fun `unavailable or disabled screen control sends nothing`() {
        choice = Choice.Unavailable("Provision the Portal token.")
        assertEquals("Provision the Portal token.", call(Operation.NAVIGATE, "button" to "home").message)

        choice = Choice.Ready("portal") { phone }
        enabled = false
        assertEquals(ScreenActions.DISABLED, call(Operation.NAVIGATE, "button" to "home").message)
        assertTrue(phone.performed.isEmpty())
    }

    private companion object {
        fun result(
            action: Action,
            after: Observation,
            ok: Boolean = true,
            error: com.colonelpanic.eva.devicecontrol.proto.ErrorInfo? = null,
            status: ExecutionStatus = ExecutionStatus.EXECUTED,
        ) = ActionResult(action.actionId, action.kind, ok, "t0", "t1", after, error = error, executionStatus = status)
    }
}
