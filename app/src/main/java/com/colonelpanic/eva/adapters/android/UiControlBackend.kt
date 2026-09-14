package com.colonelpanic.eva.adapters.android

import android.os.DeadObjectException
import android.os.RemoteException
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Screen observation and one bounded input per invocation, through the dispatcher every other
 * capability uses. The model never receives coordinates it invented back: it names an element from
 * an observation EVA recorded, and the helper rechecks that element before delivering anything.
 */
class UiControlBackend(
    private val host: DeviceControlHost,
    private val observations: ObservationStore,
    private val operation: Operation,
    /** Checked again here so a call from a stale catalog cannot outlive the user switching it off. */
    private val enabled: () -> Boolean = { true },
) : ExecutionBackend {
    enum class Operation {
        OBSERVE,
        TAP,
        SET_TEXT,
    }

    override suspend fun unavailableReason(): String? = if (enabled()) host.unavailableReason() else DISABLED

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome =
        try {
            when (operation) {
                Operation.OBSERVE -> observe()
                Operation.TAP -> act(arguments, DeviceControlProtocol.TAP, text = null)
                Operation.SET_TEXT -> act(arguments, DeviceControlProtocol.SET_TEXT, arguments["text"].orEmpty())
            }
        } catch (error: StaleObservationException) {
            ExecutionOutcome(InvocationStatus.NOT_EXECUTED, error.message ?: ObservationStore.UNKNOWN_REFERENCE)
        } catch (error: ShizukuUnavailableException) {
            ExecutionOutcome(InvocationStatus.NOT_EXECUTED, error.message ?: DeviceControlHost.SERVER_STOPPED)
        } catch (error: DeadObjectException) {
            ExecutionOutcome(InvocationStatus.UNKNOWN, HELPER_LOST)
        } catch (error: RemoteException) {
            ExecutionOutcome(InvocationStatus.UNKNOWN, HELPER_LOST)
        }

    private suspend fun observe(): ExecutionOutcome {
        val payload = parse(host.observe(TIMEOUT_MILLIS))
        if (!payload.ok()) return refused(payload)
        val screen = DeviceControlProtocol.observationOf(payload) ?: return malformed()
        return ExecutionOutcome(InvocationStatus.COMPLETED, observations.record(screen).project())
    }

    private suspend fun act(
        arguments: Map<String, String>,
        operationName: String,
        text: String?,
    ): ExecutionOutcome {
        val observation = observations.consume(arguments["observationRef"].orEmpty())
        val index = arguments["node"]?.toIntOrNull() ?: return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, NO_ELEMENT)
        val node = observation.node(index) ?: return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, NO_ELEMENT)
        if (operationName == DeviceControlProtocol.SET_TEXT && !node.editable) {
            return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "[$index] ${node.simpleClassName()} is not a text field.")
        }
        val payload = parse(host.act(DeviceControlProtocol.request(operationName, observation, node, text), TIMEOUT_MILLIS))
        if (!payload.ok()) return refused(payload)

        val delivered = payload["delivered"]?.jsonPrimitive?.boolean ?: false
        val partial = payload["partial"]?.jsonPrimitive?.boolean ?: false
        val detail = DeviceControlProtocol.detailOf(payload)
        val after =
            DeviceControlProtocol
                .observationOf(payload)
                ?.let { observations.record(it).project() }
                ?: "EVA could not read the screen afterwards; look at it again before continuing."
        val summary = if (text == null) "Tapped ${node.describe()}." else "Replaced the text in ${node.describe()}."
        return when {
            partial -> ExecutionOutcome(InvocationStatus.UNKNOWN, "${partialNote(detail)}\n$after")
            delivered -> ExecutionOutcome(InvocationStatus.COMPLETED, "$summary $DELIVERY_NOTE\n$after")
            else -> ExecutionOutcome(InvocationStatus.NOT_EXECUTED, DeviceControlProtocol.failureMessage("", detail))
        }
    }

    private fun refused(payload: JsonObject): ExecutionOutcome {
        val reason = DeviceControlProtocol.reasonOf(payload)
        val detail = DeviceControlProtocol.detailOf(payload)
        return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, DeviceControlProtocol.failureMessage(reason, detail))
    }

    private fun malformed() = ExecutionOutcome(InvocationStatus.FAILED, "The device control helper returned an unreadable screen.")

    private fun partialNote(detail: String) =
        "The input was only partly delivered: ${detail.ifBlank { "the gesture did not complete" }}. " +
            "Check the screen before trying again."

    private fun parse(payload: String) = deviceControlJson.parseToJsonElement(payload).jsonObject

    private fun JsonObject.ok() = this["ok"]?.jsonPrimitive?.boolean ?: false

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L
        const val NO_ELEMENT = "That element number is not in the screen EVA recorded. Look at the screen again."
        const val DISABLED = "Screen control is switched off in EVA's settings."
        const val HELPER_LOST =
            "The device control helper stopped before reporting a result. Look at the screen to see whether the action happened."
        const val DELIVERY_NOTE = "Input was delivered; the screen below is what followed, not proof the task is done."
    }
}
