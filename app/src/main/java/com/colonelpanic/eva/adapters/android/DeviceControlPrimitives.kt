package com.colonelpanic.eva.adapters.android

import android.annotation.SuppressLint
import android.app.UiAutomation
import android.graphics.Point
import android.graphics.Rect
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit

/**
 * Portal's `state_full` shape, read through UiAutomation, so the device-task backend maps Shizuku and
 * Portal screens identically. Runs only inside the shell helper.
 */
@RequiresApi(30)
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
internal object PortalState {
    private const val MAX_NODES = 800
    private const val MAX_DEPTH = 60
    private const val MAX_TEXT_CHARS = 500

    /**
     * The focused field's whole value, up to the longest text entry the backend accepts, so read-back
     * can confirm it. Only one node is focused, which keeps the reply within Binder's limit.
     */
    private const val MAX_FIELD_CHARS = 10_000
    private const val POLL_MILLIS = 100L

    fun capture(
        automation: UiAutomation,
        rootDeadline: Long,
    ): JsonObject {
        var root = automation.rootInActiveWindow
        while (root == null && SystemClock.elapsedRealtime() < rootDeadline) {
            SystemClock.sleep(POLL_MILLIS)
            root = automation.rootInActiveWindow
        }
        val (width, height) = screenSize(root)
        return buildJsonObject {
            if (root != null) put("a11y_tree", node(root, 0, intArrayOf(0)))
            put(
                "phone_state",
                buildJsonObject {
                    put("packageName", root?.packageName?.toString().orEmpty())
                    put(
                        "keyboardVisible",
                        runCatching { automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false),
                    )
                    systemFlag("power", "android.os.IPowerManager", "isInteractive")?.let { put("isInteractive", it) }
                    systemFlag("window", "android.view.IWindowManager", "isKeyguardLocked")?.let { put("isLocked", it) }
                },
            )
            put(
                "device_context",
                buildJsonObject {
                    put(
                        "screen_bounds",
                        buildJsonObject {
                            put("width", width)
                            put("height", height)
                        },
                    )
                },
            )
        }
    }

    private fun node(
        node: AccessibilityNodeInfo,
        depth: Int,
        count: IntArray,
    ): JsonObject {
        count[0]++
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return buildJsonObject {
            put("className", node.className?.toString().orEmpty())
            put(
                "text",
                node.text
                    ?.toString()
                    .orEmpty()
                    .take(if (node.isEditable && node.isFocused) MAX_FIELD_CHARS else MAX_TEXT_CHARS),
            )
            put(
                "contentDescription",
                node.contentDescription
                    ?.toString()
                    .orEmpty()
                    .take(MAX_TEXT_CHARS),
            )
            put(
                "hint",
                node.hintText
                    ?.toString()
                    .orEmpty()
                    .take(MAX_TEXT_CHARS),
            )
            put("resourceId", node.viewIdResourceName.orEmpty())
            put(
                "boundsInScreen",
                buildJsonObject {
                    put("left", bounds.left)
                    put("top", bounds.top)
                    put("right", bounds.right)
                    put("bottom", bounds.bottom)
                },
            )
            put("isVisibleToUser", node.isVisibleToUser)
            put("isClickable", node.isClickable)
            put("isLongClickable", node.isLongClickable)
            put("isEditable", node.isEditable)
            put("isScrollable", node.isScrollable)
            put("isCheckable", node.isCheckable)
            put("isChecked", node.isChecked)
            put("isFocused", node.isFocused)
            put("isEnabled", node.isEnabled)
            put("isSelected", node.isSelected)
            put("isPassword", node.isPassword)
            put("isShowingHintText", node.isShowingHintText)
            if (node.collectionInfo != null) put("collectionInfo", JsonObject(emptyMap()))
            val children =
                buildList {
                    if (depth < MAX_DEPTH) {
                        for (index in 0 until node.childCount) {
                            if (count[0] >= MAX_NODES) break
                            node.getChild(index)?.let { add(node(it, depth + 1, count)) }
                        }
                    }
                }
            put("children", JsonArray(children))
        }
    }

    private fun screenSize(root: AccessibilityNodeInfo?): Pair<Int, Int> {
        runCatching {
            val globalType = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val global = globalType.getMethod("getInstance").invoke(null)
            val display =
                globalType
                    .getMethod("getRealDisplay", Int::class.javaPrimitiveType)
                    .invoke(global, Display.DEFAULT_DISPLAY) as Display
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            return size.x to size.y
        }
        val bounds = Rect()
        root?.getBoundsInScreen(bounds)
        return bounds.width() to bounds.height()
    }

    /** Best effort; an unreadable flag is omitted and the observation keeps its default. */
    private fun systemFlag(
        service: String,
        type: String,
        method: String,
    ): Boolean? =
        runCatching {
            val binder =
                Class
                    .forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, service) as IBinder
            val proxy = Class.forName("$type\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            proxy.javaClass.getMethod(method).invoke(proxy) as Boolean
        }.getOrNull()
}

/** The fixed Portal primitives `PrimitivePlanner` emits, each validated again here. */
@RequiresApi(30)
internal object PortalCommands {
    private const val TAP_MILLIS = 50L
    private const val FRAME_MILLIS = 16L
    private const val MAX_OUTPUT_CHARS = 4_000
    private val allowedGlobals = setOf(1, 2, 4, 8)
    private val allowedKeys = setOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MOVE_END)
    private val packageRe = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val activityRe = Regex("[A-Za-z0-9_.$]{1,255}")

    class Failure(
        detail: String,
    ) : Exception(detail)

    fun run(
        automation: UiAutomation,
        method: String,
        params: JsonObject,
        deadline: Long,
    ) {
        when (method) {
            "tap" -> {
                val x = params.int("x")
                val y = params.int("y")
                gesture(automation, x, y, x, y, TAP_MILLIS)
            }

            "swipe" -> {
                val duration = params.int("duration").toLong()
                require(duration in 1..2_000) { "Invalid swipe duration" }
                gesture(automation, params.int("startX"), params.int("startY"), params.int("endX"), params.int("endY"), duration)
            }

            "global" -> {
                val action = params.int("action")
                require(action in allowedGlobals) { "Unsupported global action" }
                if (!automation.performGlobalAction(action)) throw Failure("the system refused the action")
            }

            "keyboard/key" -> {
                val code = params.int("key_code")
                require(code in allowedKeys) { "Unsupported key" }
                val now = SystemClock.uptimeMillis()
                key(automation, KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
                key(automation, KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
            }

            "keyboard/input" -> {
                input(
                    automation,
                    String(Base64.decode(params.string("base64_text"), Base64.DEFAULT), Charsets.UTF_8),
                    params.getValue("clear").jsonPrimitive.boolean,
                )
            }

            "app" -> {
                val pkg = params.string("package")
                require(packageRe.matches(pkg)) { "Invalid package" }
                val component =
                    params["activity"]?.jsonPrimitive?.contentOrNull?.let { activity ->
                        require(activityRe.matches(activity)) { "Invalid activity" }
                        "$pkg/$activity"
                    } ?: launcherComponent(pkg, deadline)
                start(listOf("-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-n", component), deadline)
            }

            "app/deep-link" -> {
                val url = params.string("deepLink")
                require(url.length <= 2048 && url.none(Char::isWhitespace) && (url.startsWith("https://") || url.startsWith("http://"))) {
                    "Invalid URL"
                }
                val pkg = params["package"]?.jsonPrimitive?.contentOrNull
                if (pkg != null) require(packageRe.matches(pkg)) { "Invalid package" }
                start(listOf("-a", "android.intent.action.VIEW", "-d", url) + listOfNotNull(pkg), deadline)
            }

            else -> {
                throw IllegalArgumentException("Unsupported command")
            }
        }
    }

    /** A press at one point is a tap or long press; any failure after DOWN is cancelled so nothing stays held. */
    private fun gesture(
        automation: UiAutomation,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMillis: Long,
    ) {
        val downTime = SystemClock.uptimeMillis()
        if (!inject(automation, motion(downTime, downTime, MotionEvent.ACTION_DOWN, startX.toFloat(), startY.toFloat()))) {
            throw Failure("touch was not accepted")
        }
        var x = startX.toFloat()
        var y = startY.toFloat()
        try {
            val steps = (durationMillis / FRAME_MILLIS).coerceAtLeast(1)
            for (step in 1..steps) {
                val due = downTime + durationMillis * step / steps
                SystemClock.sleep((due - SystemClock.uptimeMillis()).coerceAtLeast(0))
                x = startX + (endX - startX) * step.toFloat() / steps
                y = startY + (endY - startY) * step.toFloat() / steps
                if (step < steps && (startX != endX || startY != endY)) {
                    check(inject(automation, motion(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y)))
                }
            }
            check(inject(automation, motion(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)))
        } catch (error: Exception) {
            inject(automation, motion(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, x, y))
            throw Failure("the gesture was only partly delivered")
        }
    }

    private fun motion(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ) = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }

    private fun inject(
        automation: UiAutomation,
        event: MotionEvent,
    ): Boolean =
        try {
            automation.injectInputEvent(event, true)
        } finally {
            event.recycle()
        }

    private fun key(
        automation: UiAutomation,
        event: KeyEvent,
    ) {
        val sourced = KeyEvent.changeFlags(event, event.flags).apply { source = InputDevice.SOURCE_KEYBOARD }
        if (!automation.injectInputEvent(sourced, true)) throw Failure("the key was not accepted")
    }

    /** Replacement goes through the focused field; appending types characters at its cursor. */
    private fun input(
        automation: UiAutomation,
        text: String,
        clear: Boolean,
    ) {
        if (clear) {
            val field =
                automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                    ?: throw Failure("no text field is focused")
            val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) throw Failure("the field did not accept new text")
            return
        }
        val events =
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
                ?: throw Failure("these characters cannot be typed")
        for (event in events) {
            val sourced = KeyEvent.changeFlags(event, event.flags).apply { source = InputDevice.SOURCE_KEYBOARD }
            if (!automation.injectInputEvent(sourced, true)) throw Failure("typing was only partly delivered")
        }
    }

    private fun launcherComponent(
        pkg: String,
        deadline: Long,
    ): String {
        val output =
            exec(
                listOf(
                    "cmd",
                    "package",
                    "resolve-activity",
                    "--brief",
                    "-a",
                    "android.intent.action.MAIN",
                    "-c",
                    "android.intent.category.LAUNCHER",
                    pkg,
                ),
                deadline,
            )
        return output.lineSequence().map(String::trim).lastOrNull { it.startsWith("$pkg/") }
            ?: throw Failure("$pkg not found")
    }

    private fun start(
        intent: List<String>,
        deadline: Long,
    ) {
        val output = exec(listOf("am", "start") + intent, deadline)
        if (output.lineSequence().any { it.startsWith("Error") || it.contains("Exception") }) {
            throw Failure(output.lineSequence().first { it.startsWith("Error") || it.contains("Exception") }.take(200))
        }
    }

    /** Fixed argv, no shell: arguments were validated above and cannot be reinterpreted. */
    private fun exec(
        command: List<String>,
        deadline: Long,
    ): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        try {
            var output = ""
            val reader =
                Thread {
                    output =
                        process.inputStream
                            .bufferedReader()
                            .readText()
                            .take(MAX_OUTPUT_CHARS)
                }
            reader.start()
            if (!process.waitFor((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1), TimeUnit.MILLISECONDS)) {
                throw Failure("${command.first()} did not finish in time")
            }
            reader.join(1_000)
            return output
        } finally {
            process.destroy()
        }
    }

    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
