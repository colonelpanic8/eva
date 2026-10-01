package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.devicecontrol.proto.Bounds
import com.colonelpanic.eva.devicecontrol.proto.CAPPED_NOTICE
import com.colonelpanic.eva.devicecontrol.proto.Element
import com.colonelpanic.eva.devicecontrol.proto.Observation
import com.colonelpanic.eva.devicecontrol.proto.Orientation
import com.colonelpanic.eva.devicecontrol.proto.ProtocolJson
import com.colonelpanic.eva.devicecontrol.proto.Role
import com.colonelpanic.eva.devicecontrol.proto.Screen
import com.colonelpanic.eva.devicecontrol.proto.renderCompact
import com.colonelpanic.eva.devicecontrol.proto.renderTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationTableTest {
    @Test fun matchesPythonRendererOnProtocolFixture() {
        val source = checkNotNull(javaClass.getResource("/protocol-v1/observation.json")).readText()
        val expected = checkNotNull(javaClass.getResource("/observation-table.txt")).readText().trimEnd()
        assertEquals(expected, ProtocolJson.decodeFromString(Observation.serializer(), source).renderTable())
    }

    @Test fun rendersTreeFlagsAndRedactsPasswords() {
        val screen =
            Observation(
                "obs",
                "2026-01-01T00:00:00Z",
                "fake",
                screen = Screen(100, 200, Orientation.PORTRAIT),
                elements =
                    listOf(
                        Element(
                            0,
                            Role.EDIT_TEXT,
                            "secret",
                            bounds = Bounds(1, 2, 30, 40),
                            editable = true,
                            focused = true,
                            password = true,
                            depth = 0,
                        ),
                        Element(
                            1,
                            Role.SWITCH,
                            "A\nB",
                            bounds = Bounds(1, 40, 30, 60),
                            checkable = true,
                            checked = true,
                            depth = 1,
                            parentIndex = 0,
                        ),
                    ),
            )
        val rendered = screen.renderTable()
        assertFalse(rendered.contains("secret"))
        assertTrue(rendered.contains("[0] edit_text <password> @1,2,30,40 efp"))
        assertTrue(rendered.contains(" [1] switch \"A\\nB\" @1,40,30,60 kx"))
        assertTrue(screen.renderTable(maxElements = 1).endsWith("… 1 more elements not shown"))
    }

    @Test fun compactProjectionKeepsIndicesDropsContainersAndRedactsPasswords() {
        val screen =
            Observation(
                "obs",
                "2026-01-01T00:00:00Z",
                "fake",
                packageName = "com.example",
                screen = Screen(100, 200, Orientation.PORTRAIT),
                elements =
                    listOf(
                        Element(0, Role.OTHER, bounds = Bounds(0, 0, 100, 200), depth = 0),
                        Element(1, Role.SWITCH, "Wi-Fi", bounds = Bounds(0, 10, 100, 20), checkable = true, checked = true, depth = 1),
                        Element(2, Role.EDIT_TEXT, "hunter2", bounds = Bounds(0, 30, 100, 40), editable = true, password = true, depth = 1),
                    ),
            )

        val lines = screen.renderCompact("screen-7").lines()

        assertEquals("screen com.example 100x200 (observation screen-7)", lines[0])
        assertEquals("[1] switch \"Wi-Fi\" checked at (0,10)-(100,20)", lines[1])
        assertEquals("[2] edit_text <password> editable at (0,30)-(100,40)", lines[2])
        assertEquals(3, lines.size)
    }

    @Test fun compactProjectionStaysWithinItsBudget() {
        val screen =
            Observation(
                "obs",
                "2026-01-01T00:00:00Z",
                "fake",
                screen = Screen(100, 200, Orientation.PORTRAIT),
                elements =
                    (0 until 50).map {
                        Element(
                            it,
                            Role.BUTTON,
                            "Item $it",
                            bounds = Bounds(0, it, 100, it + 1),
                            clickable = true,
                            depth = 0,
                        )
                    },
            )

        val text = screen.renderCompact("screen-1", maxChars = 400)

        assertTrue(text.length <= 400)
        val shown = text.lines().count { it.startsWith("[") }
        assertTrue(text, text.endsWith("… ${50 - shown} more elements were not shown; scroll to reach them."))
    }

    @Test fun aCappedCaptureSaysTheScreenHadMoreInBothRendersAndOnlyThenOnTheWire() {
        val screen =
            Observation(
                "obs",
                "2026-01-01T00:00:00Z",
                "fake",
                screen = Screen(100, 200, Orientation.PORTRAIT),
                elements = listOf(Element(0, Role.BUTTON, "OK", bounds = Bounds(0, 0, 10, 10), clickable = true, depth = 0)),
                elementsCapped = true,
            )
        assertTrue(screen.renderTable().endsWith(CAPPED_NOTICE))
        assertTrue(screen.renderCompact("s", maxChars = 200).endsWith(CAPPED_NOTICE))
        val wire = ProtocolJson.encodeToString(Observation.serializer(), screen)
        assertEquals(screen, ProtocolJson.decodeFromString(Observation.serializer(), wire))
        val uncapped = screen.copy(elementsCapped = false)
        assertFalse(ProtocolJson.encodeToString(Observation.serializer(), uncapped).contains("elements_capped"))
        assertFalse(uncapped.renderTable().contains(CAPPED_NOTICE))
    }
}
