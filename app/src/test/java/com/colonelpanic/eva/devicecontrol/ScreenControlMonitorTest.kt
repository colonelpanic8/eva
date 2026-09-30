package com.colonelpanic.eva.devicecontrol

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScreenControlMonitorTest {
    @Test
    fun showsBackendsInPreferenceOrderAndShizukuForDirectTools() =
        runTest {
            var enabled = true
            var backends = listOf("portal", "shizuku")
            val monitor =
                ScreenControlMonitor(
                    enabled = { enabled },
                    backends = { backends },
                    problem = { if (it == "portal") "Portal is not running." else null },
                    label = { it.replaceFirstChar(Char::uppercase) },
                )

            val both = monitor.refresh()
            assertEquals(
                listOf(ScreenControlStatus.Route("Portal", "Portal is not running."), ScreenControlStatus.Route("Shizuku", null)),
                both.routes,
            )
            assertEquals(1, both.preferred)

            backends = listOf("portal")
            assertEquals(listOf("Portal", "Shizuku"), monitor.refresh().routes.map { it.name })

            backends = listOf("shizuku", "portal")
            assertEquals(listOf("Shizuku", "Portal"), monitor.refresh().routes.map { it.name })

            enabled = false
            assertFalse(monitor.refresh().enabled)
            assertEquals(ScreenControlStatus(), monitor.status.value)
        }
}
