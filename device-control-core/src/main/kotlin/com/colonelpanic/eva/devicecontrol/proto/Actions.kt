@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.colonelpanic.eva.devicecontrol.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("kind")
sealed class Action {
    abstract val actionId: String
    abstract val taskId: String
    abstract val taskRevision: Long
    abstract val boundObservationId: String

    /** Controller-set; never produced by the worker. */
    abstract val issuedAt: String?
    abstract val expiresAt: String?
    abstract val leaseId: String?
}

@Serializable
@SerialName("launch_app")
data class LaunchApp(
    @SerialName("action_id")
    override val actionId: String,
    @SerialName("task_id")
    override val taskId: String,
    @SerialName("task_revision")
    override val taskRevision: Long,
    @SerialName("bound_observation_id")
    override val boundObservationId: String,
    @SerialName("package")
    val packageName: String,
    val activity: String? = null,
    @SerialName("issued_at")
    override val issuedAt: String? = null,
    @SerialName("expires_at")
    override val expiresAt: String? = null,
    @SerialName("lease_id")
    override val leaseId: String? = null,
) : Action()

@Serializable
@SerialName("activate_element")
data class ActivateElement(
    @SerialName("action_id")
    override val actionId: String,
    @SerialName("task_id")
    override val taskId: String,
    @SerialName("task_revision")
    override val taskRevision: Long,
    @SerialName("bound_observation_id")
    override val boundObservationId: String,
    val element: Int,
    @SerialName("issued_at")
    override val issuedAt: String? = null,
    @SerialName("expires_at")
    override val expiresAt: String? = null,
    @SerialName("lease_id")
    override val leaseId: String? = null,
) : Action()

@Serializable
@SerialName("set_text")
data class SetText(
    @SerialName("action_id")
    override val actionId: String,
    @SerialName("task_id")
    override val taskId: String,
    @SerialName("task_revision")
    override val taskRevision: Long,
    @SerialName("bound_observation_id")
    override val boundObservationId: String,
    val element: Int,
    val text: String,
    val replace: Boolean = true,
    @SerialName("issued_at")
    override val issuedAt: String? = null,
    @SerialName("expires_at")
    override val expiresAt: String? = null,
    @SerialName("lease_id")
    override val leaseId: String? = null,
) : Action()

@Serializable
@SerialName("scroll")
data class Scroll(
    @SerialName("action_id")
    override val actionId: String,
    @SerialName("task_id")
    override val taskId: String,
    @SerialName("task_revision")
    override val taskRevision: Long,
    @SerialName("bound_observation_id")
    override val boundObservationId: String,
    val direction: ScrollDirection,
    val element: Int? = null,
    @SerialName("issued_at")
    override val issuedAt: String? = null,
    @SerialName("expires_at")
    override val expiresAt: String? = null,
    @SerialName("lease_id")
    override val leaseId: String? = null,
) : Action()

@Serializable
@SerialName("back")
data class Back(
    @SerialName("action_id")
    override val actionId: String,
    @SerialName("task_id")
    override val taskId: String,
    @SerialName("task_revision")
    override val taskRevision: Long,
    @SerialName("bound_observation_id")
    override val boundObservationId: String,
    @SerialName("issued_at")
    override val issuedAt: String? = null,
    @SerialName("expires_at")
    override val expiresAt: String? = null,
    @SerialName("lease_id")
    override val leaseId: String? = null,
) : Action()

@Serializable
@SerialName("home")
data class Home(
    @SerialName("action_id")
    override val actionId: String,
    @SerialName("task_id")
    override val taskId: String,
    @SerialName("task_revision")
    override val taskRevision: Long,
    @SerialName("bound_observation_id")
    override val boundObservationId: String,
    @SerialName("issued_at")
    override val issuedAt: String? = null,
    @SerialName("expires_at")
    override val expiresAt: String? = null,
    @SerialName("lease_id")
    override val leaseId: String? = null,
) : Action()

@Serializable
@SerialName("lock_screen")
data class LockScreen(
    @SerialName("action_id")
    override val actionId: String,
    @SerialName("task_id")
    override val taskId: String,
    @SerialName("task_revision")
    override val taskRevision: Long,
    @SerialName("bound_observation_id")
    override val boundObservationId: String,
    @SerialName("issued_at")
    override val issuedAt: String? = null,
    @SerialName("expires_at")
    override val expiresAt: String? = null,
    @SerialName("lease_id")
    override val leaseId: String? = null,
) : Action()

@Serializable
@SerialName("tap_point")
data class TapPoint(
    @SerialName("action_id")
    override val actionId: String,
    @SerialName("task_id")
    override val taskId: String,
    @SerialName("task_revision")
    override val taskRevision: Long,
    @SerialName("bound_observation_id")
    override val boundObservationId: String,
    val x: Int,
    val y: Int,
    val within: Int,
    @SerialName("issued_at")
    override val issuedAt: String? = null,
    @SerialName("expires_at")
    override val expiresAt: String? = null,
    @SerialName("lease_id")
    override val leaseId: String? = null,
    @SerialName("scope_override")
    val scopeOverride: Boolean = false,
) : Action()

@Serializable
@SerialName("swipe")
data class Swipe(
    @SerialName("action_id")
    override val actionId: String,
    @SerialName("task_id")
    override val taskId: String,
    @SerialName("task_revision")
    override val taskRevision: Long,
    @SerialName("bound_observation_id")
    override val boundObservationId: String,
    val start: Point,
    val end: Point,
    val within: Int,
    @SerialName("duration_ms")
    val durationMs: Int = 300,
    @SerialName("issued_at")
    override val issuedAt: String? = null,
    @SerialName("expires_at")
    override val expiresAt: String? = null,
    @SerialName("lease_id")
    override val leaseId: String? = null,
    @SerialName("scope_override")
    val scopeOverride: Boolean = false,
) : Action()

@Serializable
@SerialName("long_press")
data class LongPress(
    @SerialName("action_id")
    override val actionId: String,
    @SerialName("task_id")
    override val taskId: String,
    @SerialName("task_revision")
    override val taskRevision: Long,
    @SerialName("bound_observation_id")
    override val boundObservationId: String,
    val element: Int,
    @SerialName("issued_at")
    override val issuedAt: String? = null,
    @SerialName("expires_at")
    override val expiresAt: String? = null,
    @SerialName("lease_id")
    override val leaseId: String? = null,
    @SerialName("duration_ms")
    val durationMs: Int? = null,
    @SerialName("allow_click_fallback")
    val allowClickFallback: Boolean = false,
) : Action()

@Serializable
@SerialName("screenshot")
data class Screenshot(
    @SerialName("action_id")
    override val actionId: String,
    @SerialName("task_id")
    override val taskId: String,
    @SerialName("task_revision")
    override val taskRevision: Long,
    @SerialName("bound_observation_id")
    override val boundObservationId: String,
    @SerialName("issued_at")
    override val issuedAt: String? = null,
    @SerialName("expires_at")
    override val expiresAt: String? = null,
    @SerialName("lease_id")
    override val leaseId: String? = null,
) : Action()

/** IME action of the focused editable element. */
@Serializable
@SerialName("ime_action")
data class ImeAction(
    @SerialName("action_id")
    override val actionId: String,
    @SerialName("task_id")
    override val taskId: String,
    @SerialName("task_revision")
    override val taskRevision: Long,
    @SerialName("bound_observation_id")
    override val boundObservationId: String,
    val action: ImeActionName,
    val element: Int,
    @SerialName("issued_at")
    override val issuedAt: String? = null,
    @SerialName("expires_at")
    override val expiresAt: String? = null,
    @SerialName("lease_id")
    override val leaseId: String? = null,
) : Action()

/** http(s) URL only; no intent extras. */
@Serializable
@SerialName("open_url")
data class OpenUrl(
    @SerialName("action_id")
    override val actionId: String,
    @SerialName("task_id")
    override val taskId: String,
    @SerialName("task_revision")
    override val taskRevision: Long,
    @SerialName("bound_observation_id")
    override val boundObservationId: String,
    val url: String,
    @SerialName("package")
    val packageName: String? = null,
    @SerialName("issued_at")
    override val issuedAt: String? = null,
    @SerialName("expires_at")
    override val expiresAt: String? = null,
    @SerialName("lease_id")
    override val leaseId: String? = null,
) : Action()

/** Opens the notification shade. */
@Serializable
@SerialName("open_notifications")
data class OpenNotifications(
    @SerialName("action_id")
    override val actionId: String,
    @SerialName("task_id")
    override val taskId: String,
    @SerialName("task_revision")
    override val taskRevision: Long,
    @SerialName("bound_observation_id")
    override val boundObservationId: String,
    @SerialName("issued_at")
    override val issuedAt: String? = null,
    @SerialName("expires_at")
    override val expiresAt: String? = null,
    @SerialName("lease_id")
    override val leaseId: String? = null,
) : Action()

@Serializable
enum class ActionKind {
    @SerialName("launch_app")
    LAUNCH_APP,

    @SerialName("activate_element")
    ACTIVATE_ELEMENT,

    @SerialName("set_text")
    SET_TEXT,

    @SerialName("scroll")
    SCROLL,

    @SerialName("back")
    BACK,

    @SerialName("home")
    HOME,

    @SerialName("lock_screen")
    LOCK_SCREEN,

    @SerialName("tap_point")
    TAP_POINT,

    @SerialName("swipe")
    SWIPE,

    @SerialName("long_press")
    LONG_PRESS,

    @SerialName("screenshot")
    SCREENSHOT,

    @SerialName("ime_action")
    IME_ACTION,

    @SerialName("open_url")
    OPEN_URL,

    @SerialName("open_notifications")
    OPEN_NOTIFICATIONS,
}

@Serializable
enum class ScrollDirection {
    @SerialName("up")
    UP,

    @SerialName("down")
    DOWN,

    @SerialName("left")
    LEFT,

    @SerialName("right")
    RIGHT,
}

@Serializable
enum class ImeActionName {
    @SerialName("enter")
    ENTER,

    @SerialName("search")
    SEARCH,

    @SerialName("done")
    DONE,

    @SerialName("go")
    GO,

    @SerialName("send")
    SEND,

    @SerialName("next")
    NEXT,

    @SerialName("previous")
    PREVIOUS,
}
