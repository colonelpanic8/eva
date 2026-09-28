package com.colonelpanic.eva.devicecontrol.host

import com.colonelpanic.eva.devicecontrol.DeviceBackend
import com.colonelpanic.eva.devicecontrol.proto.Action
import com.colonelpanic.eva.devicecontrol.proto.ProtocolJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

suspend fun bindAction(
    backend: DeviceBackend,
    template: JsonObject,
    taskId: String = "host",
): Action {
    val observation = backend.observe()
    val fields = template.toMutableMap()
    fields.putIfAbsent("action_id", JsonPrimitive(UUID.randomUUID().toString()))
    fields.putIfAbsent("task_id", JsonPrimitive(taskId))
    fields.putIfAbsent("task_revision", JsonPrimitive(0))
    fields.putIfAbsent("bound_observation_id", JsonPrimitive(observation.observationId))
    return ProtocolJson.decodeFromJsonElement(Action.serializer(), JsonObject(fields))
}
