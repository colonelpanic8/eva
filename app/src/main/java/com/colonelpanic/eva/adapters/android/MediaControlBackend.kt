package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Drives whatever is already playing through Android's media session, so one implementation
 * covers every app that publishes transport controls instead of one adapter per music app.
 *
 * A transport command is a request to another process, answered whenever that process gets to
 * it, so the backend reads the session back before reporting rather than restating the command.
 */
class MediaControlBackend(
    private val access: MediaSessionAccess,
    private val operation: Operation,
    private val settle: suspend () -> Unit = { delay(SETTLE_MILLIS) },
) : ExecutionBackend {
    enum class Operation {
        CONTROL,
        STATUS,
        VOLUME,
    }

    /** Media sessions answer from the background, so unlike an intent this needs no screen. */
    override suspend fun unavailableReason(): String? = null

    /** Reading and driving a session are synchronous binder calls, so they stay off the main thread. */
    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome =
        withContext(Dispatchers.IO) {
            when (operation) {
                Operation.STATUS -> status()
                Operation.CONTROL -> control(arguments)
                Operation.VOLUME -> volume(arguments)
            }
        }

    private fun status(): ExecutionOutcome {
        val volume = access.volume()
        if (!access.observable()) {
            return ExecutionOutcome(
                InvocationStatus.NOT_EXECUTED,
                listOfNotNull(NEEDS_ACCESS, volume?.let(MediaText::volume)).joinToString(" "),
            )
        }
        return ExecutionOutcome(InvocationStatus.COMPLETED, MediaText.describe(access.sessions(), volume))
    }

    private suspend fun control(arguments: Map<String, String>): ExecutionOutcome {
        val command =
            MediaCommand.of(arguments.getValue("action"))
                ?: return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, UNKNOWN_ACTION)
        val app = arguments["app"]?.trim()?.takeIf(String::isNotBlank)
        val observable = access.observable()
        val sessions = if (observable) access.sessions() else emptyList()
        return when (val plan = MediaRouting.plan(observable, sessions, app, command)) {
            is MediaPlan.Refuse -> ExecutionOutcome(InvocationStatus.NOT_EXECUTED, plan.message)
            is MediaPlan.MediaButton -> button(command, observable)
            is MediaPlan.Control -> drive(plan.target, command)
        }
    }

    private fun button(
        command: MediaCommand,
        observable: Boolean,
    ): ExecutionOutcome =
        if (access.sendMediaButton(command)) {
            ExecutionOutcome(InvocationStatus.HANDED_OFF, MediaText.buttonSent(command, observable))
        } else {
            ExecutionOutcome(InvocationStatus.FAILED, NO_AUDIO_SERVICE)
        }

    private suspend fun drive(
        target: MediaSnapshot,
        command: MediaCommand,
    ): ExecutionOutcome {
        val resolved = command.resolve(target)
        if (!access.send(target.packageName, resolved)) {
            return ExecutionOutcome(
                InvocationStatus.NOT_EXECUTED,
                "${target.appLabel} stopped publishing media controls before EVA could send ${resolved.argument}. Nothing was sent.",
            )
        }
        settle()
        return MediaText.confirm(resolved, target, access.sessions().firstOrNull { it.packageName == target.packageName })
    }

    private fun volume(arguments: Map<String, String>): ExecutionOutcome {
        val action =
            VolumeAction.of(arguments.getValue("action"))
                ?: return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, UNKNOWN_ACTION)
        val percent = arguments["percent"]?.trim()?.toIntOrNull()
        val report =
            access.changeVolume(action, percent)
                ?: return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, VOLUME_REFUSED)
        return ExecutionOutcome(InvocationStatus.COMPLETED, MediaText.volume(report))
    }

    companion object {
        /** Long enough for a media app to answer and republish its state, short enough to speak over. */
        const val SETTLE_MILLIS = 600L
        const val NEEDS_ACCESS =
            "EVA cannot see what is playing without notification access. Turn on media controls in EVA's settings; " +
                "pausing and skipping still work without it, unconfirmed."
        const val VOLUME_REFUSED =
            "Android refused the volume change. Do Not Disturb blocks it unless EVA is given notification policy access."
        const val NO_AUDIO_SERVICE = "This phone's audio service did not accept a media button. Nothing was sent."
        const val UNKNOWN_ACTION = "That is not an action this can perform. Nothing was sent."
    }
}
