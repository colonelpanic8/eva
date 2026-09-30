package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.adapters.android.DeviceControlHost
import com.colonelpanic.eva.devicecontrol.portal.PortalCommand
import com.colonelpanic.eva.devicecontrol.portal.PortalCommandFailure
import com.colonelpanic.eva.devicecontrol.portal.PortalTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Serves Portal's transport from EVA's Shizuku helper, so both backends share planning, rechecks and settling. */
class ShizukuPortalTransport(
    private val readState: suspend () -> String,
    private val send: suspend (String, JsonObject) -> String,
    private val capture: suspend () -> ByteArray,
) : PortalTransport {
    constructor(host: DeviceControlHost) : this(
        { host.state(TIMEOUT_MILLIS, MAX_STATE_BYTES) },
        { method, params -> host.command(method, params, TIMEOUT_MILLIS) },
        { host.screenshot(TIMEOUT_MILLIS, MAX_SCREENSHOT_BYTES) },
    )

    override suspend fun state(): JsonObject {
        val reply = reply(readState())
        return reply["state"]?.jsonObject ?: error("The helper returned no screen state")
    }

    override suspend fun command(command: PortalCommand) {
        reply(send(command.method, command.params))
    }

    override suspend fun screenshot(): ByteArray = capture()

    private fun reply(raw: String): JsonObject {
        val reply = Json.parseToJsonElement(raw).jsonObject
        if (reply["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            throw PortalCommandFailure(reply["detail"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
        return reply
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L
        const val MAX_SCREENSHOT_BYTES = 24L * 1024 * 1024
        const val MAX_STATE_BYTES = 32L * 1024 * 1024
    }
}
