package com.colonelpanic.eva.devicecontrol.testing

import com.colonelpanic.eva.devicecontrol.DeviceBackend
import com.colonelpanic.eva.devicecontrol.proto.Action
import com.colonelpanic.eva.devicecontrol.proto.ActionResult
import com.colonelpanic.eva.devicecontrol.proto.Observation

/** Scripted device I/O, including delayed actions and failures for cancellation tests. */
class FakeDeviceBackend(
    var screen: Observation,
    var onPerform: suspend (Action) -> ActionResult,
) : DeviceBackend {
    val actions = mutableListOf<Action>()
    var observations = 0
        private set

    override suspend fun observe(): Observation {
        observations++
        return screen
    }

    override suspend fun perform(action: Action): ActionResult {
        check(action.boundObservationId == screen.observationId)
        actions += action
        return onPerform(action).also { it.observation?.let { next -> screen = next } }
    }
}
