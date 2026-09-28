package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.adapters.android.DeviceControlProtocol
import com.colonelpanic.eva.adapters.android.UiObservation
import com.colonelpanic.eva.devicecontrol.proto.Action
import com.colonelpanic.eva.devicecontrol.proto.ActionResult
import com.colonelpanic.eva.devicecontrol.proto.ActivateElement
import com.colonelpanic.eva.devicecontrol.proto.Bounds
import com.colonelpanic.eva.devicecontrol.proto.DuplicateAction
import com.colonelpanic.eva.devicecontrol.proto.Element
import com.colonelpanic.eva.devicecontrol.proto.ElementNotFound
import com.colonelpanic.eva.devicecontrol.proto.ErrorInfo
import com.colonelpanic.eva.devicecontrol.proto.ExecutionStatus
import com.colonelpanic.eva.devicecontrol.proto.Observation
import com.colonelpanic.eva.devicecontrol.proto.Orientation
import com.colonelpanic.eva.devicecontrol.proto.Role
import com.colonelpanic.eva.devicecontrol.proto.Screen
import com.colonelpanic.eva.devicecontrol.proto.SetText
import com.colonelpanic.eva.devicecontrol.proto.SetTextDetails
import com.colonelpanic.eva.devicecontrol.proto.StaleObservation
import com.colonelpanic.eva.devicecontrol.proto.TextMismatch
import com.colonelpanic.eva.devicecontrol.proto.Unsupported
import com.colonelpanic.eva.devicecontrol.proto.kind
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID

/** Keeps the existing helper's exact target recheck; unimplemented protocol actions are explicit. */
class ShizukuDeviceBackend(
    private val read: suspend () -> String,
    private val input: suspend (JsonObject) -> String,
) : DeviceBackend {
    private val mutex = Mutex()
    private var last: UiObservation? = null
    private val ids = mutableSetOf<String>()

    private fun parse(raw: String) = Json.parseToJsonElement(raw).jsonObject

    private fun capture(payload: JsonObject): Observation {
        val screen = checkNotNull(DeviceControlProtocol.observationOf(payload))
        val value = UiObservation.parse(screen, UUID.randomUUID().toString(), 0)
        last = value
        return map(value)
    }

    private fun map(value: UiObservation) =
        Observation(
            value.reference,
            Instant.now().toString(),
            "shizuku",
            value.packageName,
            screen = Screen(value.width, value.height, if (value.width > value.height) Orientation.LANDSCAPE else Orientation.PORTRAIT),
            elements =
                value.nodes.map { node ->
                    Element(
                        node.index,
                        when {
                            node.editable -> Role.EDIT_TEXT
                            node.scrollable -> Role.SCROLLABLE
                            node.clickable -> Role.BUTTON
                            else -> Role.TEXT
                        },
                        if (node.password) null else node.text,
                        if (node.password) null else node.description,
                        bounds = Bounds(node.left, node.top, node.right, node.bottom),
                        clickable = node.clickable,
                        editable = node.editable,
                        scrollable = node.scrollable,
                        focused = node.focused,
                        password = node.password,
                        depth = 0,
                    )
                },
        )

    override suspend fun observe(): Observation = mutex.withLock { capture(parse(read())) }

    override suspend fun perform(action: Action): ActionResult =
        mutex.withLock {
            val started = Instant.now().toString()

            fun refused(error: ErrorInfo) =
                ActionResult(
                    action.actionId,
                    action.kind,
                    false,
                    started,
                    Instant.now().toString(),
                    last?.let(::map),
                    error = error,
                    executionStatus = ExecutionStatus.NOT_DISPATCHED,
                )
            val before = last
            if (before == null ||
                before.reference != action.boundObservationId
            ) {
                return@withLock refused(StaleObservation(action.actionId, action.boundObservationId, before?.reference))
            }
            if (!ids.add(action.actionId)) return@withLock refused(DuplicateAction(action.actionId, action.boundObservationId))
            val index =
                when (action) {
                    is ActivateElement -> action.element
                    is SetText -> action.element
                    else -> return@withLock refused(Unsupported(action.actionId, action.boundObservationId, action.kind, "shizuku"))
                }
            val node = before.node(index) ?: return@withLock refused(ElementNotFound(action.actionId, action.boundObservationId, index))
            if (action is SetText &&
                (node.password || !node.editable || !action.replace)
            ) {
                return@withLock refused(Unsupported(action.actionId, action.boundObservationId, action.kind, "shizuku"))
            }
            val caller = currentCoroutineContext()
            withContext(NonCancellable) {
                caller.ensureActive()
                val request =
                    DeviceControlProtocol.request(
                        if (action is SetText) DeviceControlProtocol.SET_TEXT else DeviceControlProtocol.TAP,
                        before,
                        node,
                        (action as? SetText)?.text,
                    )
                val payload =
                    try {
                        parse(input(request))
                    } catch (_: Exception) {
                        return@withContext ActionResult(
                            action.actionId,
                            action.kind,
                            false,
                            started,
                            Instant.now().toString(),
                            null,
                            executionStatus = ExecutionStatus.OUTCOME_UNKNOWN,
                        )
                    }
                if (payload["ok"]?.jsonPrimitive?.booleanOrNull !=
                    true
                ) {
                    return@withContext refused(StaleObservation(action.actionId, action.boundObservationId, before.reference))
                }
                val delivered = payload["delivered"]?.jsonPrimitive?.booleanOrNull == true
                val partial = payload["partial"]?.jsonPrimitive?.booleanOrNull == true
                val after = runCatching { capture(payload) }.getOrNull()
                val actual = after?.elements?.firstOrNull { it.index == index }?.text
                val details = (action as? SetText)?.let { SetTextDetails(index, actual == it.text, it.text, actual) }
                ActionResult(
                    action.actionId,
                    action.kind,
                    delivered && !partial && after != null && details?.verified != false,
                    started,
                    Instant.now().toString(),
                    after,
                    details = details,
                    error =
                        if (details?.verified ==
                            false
                        ) {
                            TextMismatch(action.actionId, action.boundObservationId, index, details.expected, details.actual)
                        } else {
                            null
                        },
                    executionStatus =
                        if (partial ||
                            (delivered && after == null)
                        ) {
                            ExecutionStatus.OUTCOME_UNKNOWN
                        } else if (delivered) {
                            ExecutionStatus.EXECUTED
                        } else {
                            ExecutionStatus.NOT_DISPATCHED
                        },
                )
            }
        }
}
