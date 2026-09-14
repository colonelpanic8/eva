package com.colonelpanic.eva.adapters.android

import android.annotation.SuppressLint
import android.app.UiAutomation
import android.graphics.Point
import android.graphics.Rect
import android.os.Bundle
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.system.exitProcess

/**
 * Runs inside the Shizuku user service as shell (UID 2000), never in EVA's own process. It holds a
 * UiAutomation connection only for the duration of one call, and it accepts an element EVA already
 * observed rather than a command, so there is no general shell or planner on this side.
 */
@RequiresApi(30)
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class DeviceControlUserService : IDeviceControl.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    @Synchronized
    override fun observe(timeoutMillis: Long): String =
        session(timeoutMillis) { automation ->
            buildJsonObject {
                put("ok", true)
                put("observation", capture(automation).screen)
            }
        }

    @Synchronized
    override fun act(
        request: String,
        timeoutMillis: Long,
    ): String =
        session(timeoutMillis) { automation ->
            perform(automation, deviceControlJson.parseToJsonElement(request).jsonObject)
        }

    private fun session(
        timeoutMillis: Long,
        block: (UiAutomation) -> JsonObject,
    ): String {
        require(Process.myUid() == SHELL_UID) { "Device control must run as Android's shell user" }
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "Invalid timeout" }
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        val thread = HandlerThread("eva-device-control")
        thread.start()
        var automation: UiAutomation? = null
        val result =
            try {
                automation = connect(thread.looper)
                awaitRoot(automation, deadline)
                block(automation)
            } catch (error: Exception) {
                buildJsonObject {
                    put("ok", false)
                    put("reason", DeviceControlProtocol.REASON_ERROR)
                    put("detail", "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(MAX_DETAIL_CHARS))
                }
            } finally {
                automation?.let { runCatching { UiAutomation::class.java.getMethod("disconnect").invoke(it) } }
                thread.quitSafely()
            }
        return result.toString()
    }

    /** The hidden constructor the platform's own uiautomator command uses from a shell process. */
    private fun connect(looper: Looper): UiAutomation {
        val connectionType = Class.forName("android.app.IUiAutomationConnection")
        val connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance()
        val automation =
            UiAutomation::class.java
                .getConstructor(Looper::class.java, connectionType)
                .newInstance(looper, connection)
        UiAutomation::class.java
            .getMethod("connect", Int::class.javaPrimitiveType)
            .invoke(automation, FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        return automation
    }

    private fun awaitRoot(
        automation: UiAutomation,
        deadline: Long,
    ) {
        while (automation.rootInActiveWindow == null) {
            check(SystemClock.elapsedRealtime() < deadline) { "No active window became readable" }
            SystemClock.sleep(POLL_MILLIS)
        }
    }

    private class Capture(
        val screen: JsonObject,
        val nodes: List<AccessibilityNodeInfo>,
    ) {
        fun entry(index: Int) = screen.getValue("nodes").jsonArray[index].jsonObject
    }

    private fun capture(automation: UiAutomation): Capture {
        val root = automation.rootInActiveWindow ?: error("The active window disappeared")
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val entries = mutableListOf<JsonObject>()
        collect(root, nodes, entries, 0)
        val geometry = geometry(root)
        val screen =
            buildJsonObject {
                put("package", root.packageName?.toString().orEmpty())
                put("width", geometry.width)
                put("height", geometry.height)
                put("rotation", geometry.rotation)
                put("nodes", JsonArray(entries))
            }
        return Capture(screen, nodes)
    }

    private fun collect(
        node: AccessibilityNodeInfo?,
        nodes: MutableList<AccessibilityNodeInfo>,
        entries: MutableList<JsonObject>,
        depth: Int,
    ) {
        if (node == null || depth > MAX_DEPTH || nodes.size >= MAX_NODES) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val index = nodes.size
        nodes += node
        entries +=
            buildJsonObject {
                put("i", index)
                put(
                    "cls",
                    node.className
                        ?.toString()
                        .orEmpty()
                        .take(MAX_FIELD_CHARS),
                )
                put(
                    "text",
                    node.text
                        ?.toString()
                        .orEmpty()
                        .take(MAX_FIELD_CHARS),
                )
                put(
                    "desc",
                    node.contentDescription
                        ?.toString()
                        .orEmpty()
                        .take(MAX_FIELD_CHARS),
                )
                put("l", bounds.left)
                put("t", bounds.top)
                put("r", bounds.right)
                put("b", bounds.bottom)
                put("clickable", node.isClickable)
                put("editable", node.isEditable)
                put("focused", node.isFocused)
                put("scrollable", node.isScrollable)
            }
        for (child in 0 until node.childCount) collect(node.getChild(child), nodes, entries, depth + 1)
    }

    private data class Geometry(
        val width: Int,
        val height: Int,
        val rotation: Int,
    )

    /** Falls back to the window's own bounds when the hidden display lookup is unavailable. */
    private fun geometry(root: AccessibilityNodeInfo): Geometry {
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
            return Geometry(size.x, size.y, display.rotation)
        }
        val bounds = Rect()
        root.getBoundsInScreen(bounds)
        return Geometry(bounds.width(), bounds.height(), 0)
    }

    private fun perform(
        automation: UiAutomation,
        request: JsonObject,
    ): JsonObject {
        val operation = request.getValue("op").jsonPrimitive.content
        val expectedPackage = request.getValue("package").jsonPrimitive.content
        val nodeIndex = request.getValue("nodeIndex").jsonPrimitive.int
        val expected = request.getValue("expect").jsonObject
        val capture = capture(automation)
        val actualPackage =
            capture.screen
                .getValue("package")
                .jsonPrimitive.content
        if (actualPackage != expectedPackage) {
            return refusal(DeviceControlProtocol.REASON_PACKAGE, "showing $actualPackage")
        }
        val node = capture.nodes.getOrNull(nodeIndex) ?: return refusal(DeviceControlProtocol.REASON_MISSING, "")
        difference(expected, capture.entry(nodeIndex))?.let { return refusal(DeviceControlProtocol.REASON_CHANGED, it) }
        if (!node.isVisibleToUser) return refusal(DeviceControlProtocol.REASON_HIDDEN, "")

        val delivery =
            when (operation) {
                DeviceControlProtocol.TAP -> tap(automation, node)
                DeviceControlProtocol.SET_TEXT -> setText(node, request.getValue("text").jsonPrimitive.content)
                else -> return refusal(DeviceControlProtocol.REASON_REJECTED, "unsupported operation")
            }
        if (!delivery.delivered && !delivery.partial) {
            return refusal(DeviceControlProtocol.REASON_REJECTED, delivery.detail)
        }
        SystemClock.sleep(SETTLE_MILLIS)
        return buildJsonObject {
            put("ok", true)
            put("delivered", delivery.delivered)
            put("partial", delivery.partial)
            put("detail", delivery.detail)
            runCatching { capture(automation).screen }.getOrNull()?.let { put("observation", it) }
        }
    }

    private data class Delivery(
        val delivered: Boolean,
        val partial: Boolean = false,
        val detail: String = "",
    )

    private fun tap(
        automation: UiAutomation,
        node: AccessibilityNodeInfo,
    ): Delivery {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val downTime = SystemClock.uptimeMillis()
        val down = touch(downTime, downTime, MotionEvent.ACTION_DOWN, bounds)
        val up = touch(downTime, downTime + TAP_MILLIS, MotionEvent.ACTION_UP, bounds)
        try {
            if (!automation.injectInputEvent(down, true)) return Delivery(false, detail = "touch was not accepted")
            if (automation.injectInputEvent(up, true)) return Delivery(true)
        } finally {
            down.recycle()
            up.recycle()
        }
        // The press landed but its release did not; cancel so no view is left held down.
        val cancel = touch(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, bounds)
        try {
            automation.injectInputEvent(cancel, true)
        } finally {
            cancel.recycle()
        }
        return Delivery(false, partial = true, detail = "the press was delivered but its release was not")
    }

    private fun touch(
        downTime: Long,
        eventTime: Long,
        action: Int,
        bounds: Rect,
    ): MotionEvent =
        MotionEvent
            .obtain(downTime, eventTime, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }

    /** Replaces the whole field. Inserting at a caret is a separate operation EVA does not expose yet. */
    private fun setText(
        node: AccessibilityNodeInfo,
        text: String,
    ): Delivery {
        val arguments =
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
        val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        return Delivery(accepted, detail = if (accepted) "" else "the field did not accept new text")
    }

    /** Guards against acting on an element that moved, changed label, or became a different view. */
    private fun difference(
        expected: JsonObject,
        actual: JsonObject,
    ): String? {
        val expectedClass = expected.getValue("cls").jsonPrimitive.content
        val actualClass = actual.getValue("cls").jsonPrimitive.content
        if (expectedClass != actualClass) return "it is now $actualClass"
        if (expected.getValue("label").jsonPrimitive.content != actual.label()) return "its label changed"
        for (edge in listOf("l", "t", "r", "b")) {
            val drift = expected.getValue(edge).jsonPrimitive.int - actual.getValue(edge).jsonPrimitive.int
            if (drift > DeviceControlProtocol.BOUNDS_TOLERANCE_PX || drift < -DeviceControlProtocol.BOUNDS_TOLERANCE_PX) {
                return "it moved on screen"
            }
        }
        return null
    }

    private fun JsonObject.label(): String {
        val text = getValue("text").jsonPrimitive.content
        return text.ifBlank { getValue("desc").jsonPrimitive.content }
    }

    private fun refusal(
        reason: String,
        detail: String,
    ) = buildJsonObject {
        put("ok", false)
        put("reason", reason)
        put("detail", detail.take(MAX_DETAIL_CHARS))
    }

    private companion object {
        const val SHELL_UID = 2000
        const val MAX_TIMEOUT_MILLIS = 30_000L
        const val MAX_NODES = 200
        const val MAX_DEPTH = 40
        const val MAX_FIELD_CHARS = 200
        const val MAX_DETAIL_CHARS = 300
        const val POLL_MILLIS = 100L
        const val SETTLE_MILLIS = 350L
        const val TAP_MILLIS = 80L
        const val FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES = 0x1
    }
}
