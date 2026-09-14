package com.colonelpanic.eva.adapters.android

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UiObservationTest {
    private fun node(
        index: Int,
        className: String = "android.widget.TextView",
        text: String = "",
        description: String = "",
        clickable: Boolean = false,
        editable: Boolean = false,
        top: Int = index * 100,
    ) = buildJsonObject {
        put("i", index)
        put("cls", className)
        put("text", text)
        put("desc", description)
        put("l", 48)
        put("t", top)
        put("r", 1032)
        put("b", top + 90)
        put("clickable", clickable)
        put("editable", editable)
        put("focused", false)
        put("scrollable", false)
    }

    private fun screen(nodes: List<JsonObject>) =
        buildJsonObject {
            put("package", "com.android.settings")
            put("width", 1080)
            put("height", 1920)
            put("rotation", 0)
            put("nodes", JsonArray(nodes))
        }

    @Test
    fun `projection keeps original element numbers and drops unlabelled containers`() {
        val payload =
            screen(
                listOf(
                    node(0, className = "android.widget.FrameLayout"),
                    node(1, className = "android.widget.LinearLayout"),
                    node(2, text = "Battery", clickable = true),
                    node(3, className = "android.widget.EditText", description = "Search", editable = true),
                ),
            )
        val projection = UiObservation.parse(payload, "obs-1", 0).project()

        assertTrue(projection.startsWith("screen com.android.settings 1080x1920 rotation 0 (observation obs-1)"))
        assertTrue(projection.contains("[2] TextView \"Battery\" clickable"))
        assertTrue(projection.contains("[3] EditText \"Search\" editable"))
        assertTrue("layout containers carry no information for the model", !projection.contains("FrameLayout"))
    }

    @Test
    fun `projection stops at its node budget and says so`() {
        val payload = screen((0 until 40).map { node(it, text = "Item $it", clickable = true) })
        val projection = UiObservation.parse(payload, "obs-1", 0).project(maxNodes = 5)

        assertEquals(5, projection.lines().count { it.startsWith("[") })
        assertTrue(projection.contains("more elements were not shown"))
    }

    @Test
    fun `projection keeps app text on one unambiguous quoted line`() {
        val payload = screen(listOf(node(0, text = "Say \"yes\"\nnext", clickable = true)))
        val projection = UiObservation.parse(payload, "obs-1", 0).project()

        assertTrue(projection.contains("\"Say \\\"yes\\\"\\nnext\""))
        assertEquals(2, projection.lines().size)
    }

    @Test
    fun `an element number resolves to the element that was observed`() {
        val observation =
            UiObservation.parse(screen(listOf(node(0), node(1, text = "Save", clickable = true))), "obs-1", 0)

        assertEquals("Save", observation.node(1)?.label)
        assertNull(observation.node(7))
    }

    @Test
    fun `each capture gets its own reference and unknown references are refused`() {
        var now = 0L
        val store = ObservationStore(elapsedMillis = { now })
        val first = store.record(screen(listOf(node(0, text = "One", clickable = true))))
        val second = store.record(screen(listOf(node(0, text = "Two", clickable = true))))

        assertNotEquals(first.reference, second.reference)
        assertEquals("Two", store.require(second.reference).node(0)?.label)
        val error = assertThrows(StaleObservationException::class.java) { store.require("obs-nope") }
        assertEquals(ObservationStore.UNKNOWN_REFERENCE, error.message)
    }

    @Test
    fun `an observation older than its lifetime cannot be acted on`() {
        var now = 0L
        val store = ObservationStore(elapsedMillis = { now }, lifetimeMillis = 1_000)
        val observation = store.record(screen(listOf(node(0, text = "One", clickable = true))))

        now = 999
        assertEquals(observation.reference, store.require(observation.reference).reference)
        now = 1_001
        val error = assertThrows(StaleObservationException::class.java) { store.require(observation.reference) }
        assertEquals(ObservationStore.EXPIRED_REFERENCE, error.message)
    }

    @Test
    fun `an observation can authorize only one mutation`() {
        val store = ObservationStore(elapsedMillis = { 0 })
        val observation = store.record(screen(listOf(node(0, text = "Save", clickable = true))))

        assertEquals(observation.reference, store.consume(observation.reference).reference)
        val error = assertThrows(StaleObservationException::class.java) { store.consume(observation.reference) }
        assertEquals(ObservationStore.UNKNOWN_REFERENCE, error.message)
    }

    @Test
    fun `the oldest observation is dropped once the store is full`() {
        val store = ObservationStore(elapsedMillis = { 0 }, maxEntries = 2)
        val first = store.record(screen(listOf(node(0))))
        store.record(screen(listOf(node(0))))
        store.record(screen(listOf(node(0))))

        assertThrows(StaleObservationException::class.java) { store.require(first.reference) }
    }

    @Test
    fun `an action request carries the element identity the helper must re-check`() {
        val observation =
            UiObservation.parse(screen(listOf(node(0), node(1, text = "Save", clickable = true))), "obs-1", 0)
        val request =
            DeviceControlProtocol.request(
                DeviceControlProtocol.TAP,
                observation,
                observation.node(1)!!,
            )

        assertEquals(DeviceControlProtocol.TAP, request.getValue("op").jsonPrimitive.content)
        assertEquals("com.android.settings", request.getValue("package").jsonPrimitive.content)
        assertEquals(
            1,
            request
                .getValue("nodeIndex")
                .jsonPrimitive.content
                .toInt(),
        )
        val expected = request.getValue("expect").jsonObject
        assertEquals("android.widget.TextView", expected.getValue("cls").jsonPrimitive.content)
        assertEquals("Save", expected.getValue("label").jsonPrimitive.content)
        assertEquals("100", expected.getValue("t").jsonPrimitive.content)
        assertNull(request["text"])
    }

    @Test
    fun `a refusal explains itself once, with the helper detail appended only when it adds something`() {
        assertEquals(
            "The screen changed to a different app before EVA could act. Look at the screen again. (showing com.other)",
            DeviceControlProtocol.failureMessage(DeviceControlProtocol.REASON_PACKAGE, "showing com.other"),
        )
        assertEquals(
            "That element is no longer visible on screen. Look at the screen again.",
            DeviceControlProtocol.failureMessage(DeviceControlProtocol.REASON_HIDDEN, ""),
        )
        assertEquals(
            "IllegalStateException: no active window",
            DeviceControlProtocol.failureMessage(DeviceControlProtocol.REASON_ERROR, "IllegalStateException: no active window"),
        )
    }

    @Test
    fun `replacing text sends the exact replacement the model supplied`() {
        val observation =
            UiObservation.parse(
                screen(listOf(node(0, className = "android.widget.EditText", description = "Search", editable = true))),
                "obs-1",
                0,
            )
        val request =
            DeviceControlProtocol.request(
                DeviceControlProtocol.SET_TEXT,
                observation,
                observation.node(0)!!,
                text = "héllo 日本語 🙂",
            )

        assertEquals("héllo 日本語 🙂", request.getValue("text").jsonPrimitive.content)
    }
}
