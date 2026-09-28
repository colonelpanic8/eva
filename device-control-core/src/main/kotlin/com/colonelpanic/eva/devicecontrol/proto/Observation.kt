@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.colonelpanic.eva.devicecontrol.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
data class Point(
    val x: Int,
    val y: Int,
)

@Serializable
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

@Serializable
data class Screen(
    val width: Int,
    val height: Int,
    val orientation: Orientation,
)

@Serializable
data class Element(
    val index: Int,
    val role: Role,
    val text: String? = null,
    @SerialName("content_description")
    val contentDescription: String? = null,
    @SerialName("resource_id")
    val resourceId: String? = null,
    val bounds: Bounds,
    val clickable: Boolean = false,
    @SerialName("long_clickable")
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val password: Boolean = false,
    val depth: Int,
    @SerialName("parent_index")
    val parentIndex: Int? = null,
)

@Serializable
data class Observation(
    @SerialName("observation_id")
    val observationId: String,
    @SerialName("captured_at")
    val capturedAt: String,
    val backend: String,
    @SerialName("package")
    val packageName: String? = null,
    val activity: String? = null,
    val screen: Screen,
    @SerialName("keyboard_shown")
    val keyboardShown: Boolean = false,
    @SerialName("content_unavailable")
    val contentUnavailable: Boolean = false,
    val elements: List<Element> = emptyList(),
    val sequence: Long = 0,
    @SerialName("display_id")
    val displayId: Int = 0,
    @SerialName("screen_on")
    val screenOn: Boolean = true,
    val locked: Boolean = false,
    @SerialName("unavailable_reason")
    val unavailableReason: UnavailableReason? = null,
)

@Serializable
enum class Role {
    @SerialName("button")
    BUTTON,

    @SerialName("text")
    TEXT,

    @SerialName("edit_text")
    EDIT_TEXT,

    @SerialName("checkbox")
    CHECKBOX,

    @SerialName("switch")
    SWITCH,

    @SerialName("list")
    LIST,

    @SerialName("scrollable")
    SCROLLABLE,

    @SerialName("image")
    IMAGE,

    @SerialName("other")
    OTHER,
}

@Serializable
enum class Orientation {
    @SerialName("portrait")
    PORTRAIT,

    @SerialName("landscape")
    LANDSCAPE,
}

@Serializable
enum class UnavailableReason {
    @SerialName("secure_window")
    SECURE_WINDOW,

    @SerialName("a11y_unavailable")
    A11Y_UNAVAILABLE,

    @SerialName("no_content")
    NO_CONTENT,
}
