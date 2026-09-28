package com.colonelpanic.eva.devicecontrol.portal

import com.colonelpanic.eva.devicecontrol.proto.Action
import com.colonelpanic.eva.devicecontrol.proto.ActionKind
import com.colonelpanic.eva.devicecontrol.proto.ActivateElement
import com.colonelpanic.eva.devicecontrol.proto.Back
import com.colonelpanic.eva.devicecontrol.proto.Bounds
import com.colonelpanic.eva.devicecontrol.proto.Element
import com.colonelpanic.eva.devicecontrol.proto.ElementNotFound
import com.colonelpanic.eva.devicecontrol.proto.ErrorInfo
import com.colonelpanic.eva.devicecontrol.proto.Home
import com.colonelpanic.eva.devicecontrol.proto.ImeAction
import com.colonelpanic.eva.devicecontrol.proto.LaunchApp
import com.colonelpanic.eva.devicecontrol.proto.LockScreen
import com.colonelpanic.eva.devicecontrol.proto.LongPress
import com.colonelpanic.eva.devicecontrol.proto.NotActionable
import com.colonelpanic.eva.devicecontrol.proto.NotActionableReason
import com.colonelpanic.eva.devicecontrol.proto.NotLongClickable
import com.colonelpanic.eva.devicecontrol.proto.Observation
import com.colonelpanic.eva.devicecontrol.proto.OpenNotifications
import com.colonelpanic.eva.devicecontrol.proto.OpenUrl
import com.colonelpanic.eva.devicecontrol.proto.Point
import com.colonelpanic.eva.devicecontrol.proto.Screenshot
import com.colonelpanic.eva.devicecontrol.proto.Scroll
import com.colonelpanic.eva.devicecontrol.proto.ScrollDirection
import com.colonelpanic.eva.devicecontrol.proto.SetText
import com.colonelpanic.eva.devicecontrol.proto.Swipe
import com.colonelpanic.eva.devicecontrol.proto.TapPoint
import com.colonelpanic.eva.devicecontrol.proto.Unsupported
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URISyntaxException
import java.util.Base64

/** One Portal HTTP request. [gestureMs] is how long the gesture itself runs on the device. */
data class PortalCommand(
    val method: String,
    val params: JsonObject,
    val gestureMs: Long = 0,
)

sealed interface Plan {
    /** Exactly one mutating request, then a settle bounded by the launch budget if [slowStart]. */
    data class Primitive(
        val command: PortalCommand,
        val slowStart: Boolean = false,
        val foregroundPackage: String? = null,
    ) : Plan

    /** Focus tap (when needed), then input, then read-back; each input is awaited before continuing. */
    data class TypeText(
        val action: SetText,
        val target: Element,
    ) : Plan

    data object Capture : Plan
}

sealed interface Planned {
    data class Ready(
        val plan: Plan,
    ) : Planned

    data class Refused(
        val error: ErrorInfo,
    ) : Planned
}

/** Resolves an action against the observation it is bound to and translates it to Portal requests. */
object PrimitivePlanner {
    const val LONG_PRESS_MS = 1_000
    const val SCROLL_MS = 500
    const val KEYCODE_ENTER = 66
    const val KEYCODE_MOVE_END = 123
    const val SYSTEM_UI = "com.android.systemui"
    private const val SCROLL_SPAN = 0.5
    private const val SCROLL_INSET = 0.1
    private const val GLOBAL_BACK = 1
    private const val GLOBAL_HOME = 2
    private const val GLOBAL_NOTIFICATIONS = 4
    private const val GLOBAL_LOCK_SCREEN = 8

    fun plan(
        action: Action,
        observation: Observation,
        backend: String,
    ): Planned =
        try {
            Planned.Ready(planChecked(action, observation, backend))
        } catch (e: Refusal) {
            Planned.Refused(e.error)
        }

    private fun planChecked(
        action: Action,
        observation: Observation,
        backend: String,
    ): Plan {
        val checks = Checks(action, observation)
        return when (action) {
            is ActivateElement -> {
                val element = checks.enabled(checks.resolve(action.element))
                if (!(element.clickable || element.checkable)) throw checks.reject(element, NotActionableReason.NOT_CLICKABLE)
                Plan.Primitive(tap(element.bounds.center()))
            }

            is TapPoint -> {
                val element = checks.enabled(checks.resolve(action.within))
                val point = Point(action.x, action.y)
                if (!element.bounds.contains(point)) throw checks.reject(element, NotActionableReason.OUT_OF_BOUNDS)
                Plan.Primitive(tap(point))
            }

            is Swipe -> {
                val element = checks.enabled(checks.resolve(action.within))
                if (!(element.bounds.contains(action.start) && element.bounds.contains(action.end))) {
                    throw checks.reject(element, NotActionableReason.OUT_OF_BOUNDS)
                }
                Plan.Primitive(swipe(action.start, action.end, action.durationMs))
            }

            is LongPress -> {
                val element = checks.enabled(checks.resolve(action.element))
                if (!(element.longClickable || (action.allowClickFallback && element.clickable))) {
                    throw Refusal(NotLongClickable(action.actionId, observation.observationId, element.index))
                }
                val center = element.bounds.center()
                Plan.Primitive(swipe(center, center, action.durationMs ?: LONG_PRESS_MS))
            }

            is Scroll -> {
                val target =
                    action.element?.let { index ->
                        checks.resolve(index).also {
                            if (!it.scrollable) throw checks.reject(it, NotActionableReason.NOT_SCROLLABLE)
                        }
                    }
                val (start, end) = scrollPath(scrollBounds(observation, target), action.direction)
                Plan.Primitive(swipe(start, end, SCROLL_MS))
            }

            is SetText -> {
                val element = checks.enabled(checks.resolve(action.element))
                if (!element.editable) throw checks.reject(element, NotActionableReason.NOT_EDITABLE)
                Plan.TypeText(action, element)
            }

            is ImeAction -> {
                val element = checks.enabled(checks.resolve(action.element))
                if (!element.editable) throw checks.reject(element, NotActionableReason.NOT_EDITABLE)
                if (!element.focused) throw checks.reject(element, NotActionableReason.NOT_FOCUSED)
                // Portal has no editor-action call; Enter runs the field's own configured IME action.
                Plan.Primitive(key(KEYCODE_ENTER))
            }

            is Back -> {
                Plan.Primitive(global(GLOBAL_BACK))
            }

            is Home -> {
                Plan.Primitive(global(GLOBAL_HOME))
            }

            is LockScreen -> {
                Plan.Primitive(global(GLOBAL_LOCK_SCREEN))
            }

            is OpenNotifications -> {
                Plan.Primitive(global(GLOBAL_NOTIFICATIONS), foregroundPackage = SYSTEM_UI)
            }

            is LaunchApp -> {
                Plan.Primitive(
                    PortalCommand(
                        "app",
                        buildJsonObject {
                            put("package", action.packageName)
                            action.activity?.let { put("activity", it) }
                            put("stopBeforeLaunch", false)
                        },
                    ),
                    slowStart = true,
                    foregroundPackage = action.packageName,
                )
            }

            is OpenUrl -> {
                if (!isWebUrl(action.url)) {
                    throw Refusal(Unsupported(action.actionId, observation.observationId, ActionKind.OPEN_URL, backend))
                }
                Plan.Primitive(
                    PortalCommand(
                        "app/deep-link",
                        buildJsonObject {
                            put("deepLink", action.url)
                            action.packageName?.let { put("package", it) }
                        },
                    ),
                    slowStart = true,
                    foregroundPackage = action.packageName,
                )
            }

            is Screenshot -> {
                Plan.Capture
            }
        }
    }

    /** What to type into the focused field, following the host adapter's append rules. */
    fun input(
        action: SetText,
        current: String,
        password: Boolean,
    ): TextInput =
        when {
            action.replace -> TextInput(null, inputCommand(action.text, clear = true), action.text)
            password -> TextInput(key(KEYCODE_MOVE_END), inputCommand(action.text, clear = false), current + action.text)
            else -> TextInput(null, inputCommand(current + action.text, clear = true), current + action.text)
        }

    data class TextInput(
        val moveEnd: PortalCommand?,
        val input: PortalCommand,
        val expected: String,
    )

    fun tap(point: Point) =
        PortalCommand(
            "tap",
            buildJsonObject {
                put("x", point.x)
                put("y", point.y)
            },
            gestureMs = TAP_MS,
        )

    fun scrollPath(
        bounds: Bounds,
        direction: ScrollDirection,
    ): Pair<Point, Point> {
        val c = bounds.center()
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        val dx = minOf((width * SCROLL_SPAN / 2).toInt(), (width * (0.5 - SCROLL_INSET)).toInt())
        val dy = minOf((height * SCROLL_SPAN / 2).toInt(), (height * (0.5 - SCROLL_INSET)).toInt())
        return when (direction) {
            ScrollDirection.DOWN -> Point(c.x, c.y + dy) to Point(c.x, c.y - dy)
            ScrollDirection.UP -> Point(c.x, c.y - dy) to Point(c.x, c.y + dy)
            ScrollDirection.RIGHT -> Point(c.x + dx, c.y) to Point(c.x - dx, c.y)
            ScrollDirection.LEFT -> Point(c.x - dx, c.y) to Point(c.x + dx, c.y)
        }
    }

    private const val TAP_MS = 50L

    private fun swipe(
        start: Point,
        end: Point,
        durationMs: Int,
    ) = PortalCommand(
        "swipe",
        buildJsonObject {
            put("startX", start.x)
            put("startY", start.y)
            put("endX", end.x)
            put("endY", end.y)
            put("duration", durationMs)
        },
        gestureMs = durationMs.toLong(),
    )

    private fun global(id: Int) = PortalCommand("global", buildJsonObject { put("action", id) })

    private fun key(code: Int) = PortalCommand("keyboard/key", buildJsonObject { put("key_code", code) })

    private fun inputCommand(
        text: String,
        clear: Boolean,
    ) = PortalCommand(
        "keyboard/input",
        buildJsonObject {
            put("base64_text", Base64.getEncoder().encodeToString(text.toByteArray()))
            put("clear", clear)
        },
    )

    private fun scrollBounds(
        observation: Observation,
        target: Element?,
    ): Bounds {
        if (target != null) return target.bounds
        return observation.elements
            .filter { it.scrollable && it.bounds.right > it.bounds.left }
            .maxWithOrNull(compareBy<Element> { it.bounds.area() }.thenByDescending { it.index })
            ?.bounds
            ?: Bounds(0, 0, observation.screen.width, observation.screen.height)
    }

    private fun isWebUrl(url: String): Boolean {
        if (url.length > 2048 || url.any { it.isWhitespace() }) return false
        return try {
            val uri = URI(url)
            (uri.scheme == "http" || uri.scheme == "https") && uri.rawUserInfo == null && !uri.host.isNullOrEmpty()
        } catch (_: URISyntaxException) {
            false
        }
    }

    private class Refusal(
        val error: ErrorInfo,
    ) : Exception(null, null, false, false)

    private class Checks(
        val action: Action,
        val observation: Observation,
    ) {
        fun resolve(index: Int): Element =
            observation.elements.getOrNull(index)
                ?: throw Refusal(ElementNotFound(action.actionId, observation.observationId, index))

        fun reject(
            element: Element?,
            reason: NotActionableReason,
        ) = Refusal(NotActionable(action.actionId, observation.observationId, element?.index, reason))

        fun enabled(element: Element): Element {
            if (!element.enabled) throw reject(element, NotActionableReason.DISABLED)
            return element
        }
    }
}

fun Bounds.center() = Point((left + right) / 2, (top + bottom) / 2)

fun Bounds.contains(point: Point) = point.x >= left && point.x < right && point.y >= top && point.y < bottom

private fun Bounds.area() = (right - left).toLong() * (bottom - top)
