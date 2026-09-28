@file:Suppress("ktlint:standard:filename")
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.colonelpanic.eva.devicecontrol.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@RequiresOptIn("Draft companion link contract; coordinate changes with M2-D host adoption.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class ExperimentalLinkProtocol

/**
 * Draft host/companion envelopes, not Portal's reverse-connection protocol.
 * The device issues a fresh opaque generation on each session; all messages bind to it.
 * Expiry strings use RFC 3339 in the device's clock domain. The gate must validate them
 * and enforce a monotonic local deadline; receiving a message never grants authority.
 */
@ExperimentalLinkProtocol
@Serializable
@JsonClassDiscriminator("kind")
sealed class LinkMessage {
    abstract val sessionGeneration: String

    /**
     * Device to host on every link connect, carrying a fresh generation. The host echoes it
     * (any `device_id`) to take or renew the lease; any other generation gets the device hello again.
     * While the device is stopped the echo is answered with `stop` instead (see the gate runbook).
     */
    @Serializable
    @SerialName("hello")
    data class Hello(
        @SerialName("session_generation")
        override val sessionGeneration: String,
        @SerialName("device_id")
        val deviceId: String,
        @SerialName("protocol_version")
        val protocolVersion: String = "v1",
    ) : LinkMessage()

    /** Device-issued; a grant does not clear the local stop latch. A renewal keeps the ID. */
    @Serializable
    @SerialName("lease_grant")
    data class LeaseGrant(
        @SerialName("session_generation")
        override val sessionGeneration: String,
        @SerialName("lease_id")
        val leaseId: String,
        @SerialName("expires_at")
        val expiresAt: String,
    ) : LinkMessage()

    /** Device-issued; invalidates this lease immediately. */
    @Serializable
    @SerialName("lease_revoke")
    data class LeaseRevoke(
        @SerialName("session_generation")
        override val sessionGeneration: String,
        @SerialName("lease_id")
        val leaseId: String,
    ) : LinkMessage()

    /** Host to device: exactly one primitive, never a batch or delayed replay. */
    @Serializable
    @SerialName("dispatch")
    data class Dispatch(
        @SerialName("session_generation")
        override val sessionGeneration: String,
        val action: Action,
        @SerialName("lease_id")
        val leaseId: String,
        @SerialName("expires_at")
        val expiresAt: String,
        @SerialName("idempotency_key")
        val idempotencyKey: String = action.actionId,
    ) : LinkMessage() {
        init {
            require(idempotencyKey == action.actionId) { "idempotency_key must equal action.action_id" }
        }
    }

    /** Device to host: result (including a failed postcondition) or a bridge error. */
    @Serializable
    @SerialName("ack")
    data class Ack(
        @SerialName("session_generation")
        override val sessionGeneration: String,
        @SerialName("action_id")
        val actionId: String,
        val result: ActionResult? = null,
        val error: ErrorInfo? = null,
    ) : LinkMessage() {
        init {
            require((result == null) != (error == null)) { "ack requires exactly one result or error" }
            require(result == null || result.actionId == actionId) { "result action_id must match ack" }
            require(error?.actionId == null || error.actionId == actionId) { "error action_id must match ack" }
        }
    }

    /** Host to device: read the screen. Needs the current generation only; allowed while stopped. */
    @Serializable
    @SerialName("observe")
    data class Observe(
        @SerialName("session_generation")
        override val sessionGeneration: String,
        @SerialName("request_id")
        val requestId: String,
    ) : LinkMessage()

    /** Device to host: answers one `observe` with exactly one observation or error. */
    @Serializable
    @SerialName("observation")
    data class ObservationReply(
        @SerialName("session_generation")
        override val sessionGeneration: String,
        @SerialName("request_id")
        val requestId: String,
        val observation: Observation? = null,
        val error: ErrorInfo? = null,
    ) : LinkMessage() {
        init {
            require((observation == null) != (error == null)) { "observation reply requires exactly one observation or error" }
        }
    }

    /** Either direction. Local stop latches before sending, even without a host connection. */
    @Serializable
    @SerialName("stop")
    data class Stop(
        @SerialName("session_generation")
        override val sessionGeneration: String,
    ) : LinkMessage()

    /** Either direction; liveness only, never renews a lease or clears stop. */
    @Serializable
    @SerialName("heartbeat")
    data class Heartbeat(
        @SerialName("session_generation")
        override val sessionGeneration: String,
    ) : LinkMessage()
}
