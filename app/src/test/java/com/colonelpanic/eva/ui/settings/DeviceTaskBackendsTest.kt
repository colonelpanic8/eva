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
        val now = 10 * 60_000L
        assertEquals("Not checked yet.", backendState(null, now))
        assertEquals("Ready.", backendState(ScreenControlStatus.Route("portal", "Portal", null, ScreenControlStatus.Health.READY), now))
        assertEquals(
            "The last screen action through it failed · 3 min ago. Shizuku couldn't read the screen: helper didn't connect",
            backendState(
                ScreenControlStatus.Route(
                    "shizuku",
                    "Shizuku",
                    "Shizuku couldn't read the screen: helper didn't connect",
                    ScreenControlStatus.Health.UNHEALTHY,
                    sinceMillis = now - 3 * 60_000 - 5_000,
                ),
                now,
            ),
        )
    }
}
