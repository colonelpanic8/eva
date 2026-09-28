package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.devicecontrol.proto.Bounds
import com.colonelpanic.eva.devicecontrol.proto.Element
import com.colonelpanic.eva.devicecontrol.proto.Observation
import com.colonelpanic.eva.devicecontrol.proto.Orientation
import com.colonelpanic.eva.devicecontrol.proto.ProtocolJson
import com.colonelpanic.eva.devicecontrol.proto.Role
import com.colonelpanic.eva.devicecontrol.proto.Screen
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
}
