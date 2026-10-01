package com.colonelpanic.eva.ui.settings

import com.colonelpanic.eva.devicecontrol.ScreenControlStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceTaskBackendsTest {
    @Test
    fun reordersAndTogglesWithoutLeavingNoBackend() {
        assertEquals(listOf("shizuku", "portal"), moveBackend(listOf("portal", "shizuku"), "shizuku", -1))
        assertEquals(listOf("portal", "shizuku"), moveBackend(listOf("portal", "shizuku"), "portal", -1))
        assertEquals(listOf("shizuku"), toggleBackend(listOf("portal", "shizuku"), "portal"))
        assertEquals(listOf("shizuku"), toggleBackend(listOf("shizuku"), "shizuku"))
        assertEquals(listOf("shizuku", "portal"), toggleBackend(listOf("shizuku"), "portal"))
        assertEquals(listOf("shizuku", "portal"), backendRows(listOf("shizuku")))
    }

    @Test
    fun aBackendNobodyCheckedClaimsNothingAndOthersSayWhy() {
        assertEquals("Not checked yet.", backendState(null))
        assertEquals("Ready.", backendState(ScreenControlStatus.Route("Portal", null, ScreenControlStatus.Health.READY)))
        assertEquals(
            "The last screen action through it failed. Shizuku couldn't read the screen: helper didn't connect",
            backendState(
                ScreenControlStatus.Route(
                    "Shizuku",
                    "Shizuku couldn't read the screen: helper didn't connect",
                    ScreenControlStatus.Health.UNHEALTHY,
                ),
            ),
        )
    }
}
