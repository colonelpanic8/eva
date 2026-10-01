package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.devicecontrol.ScreenControlStatus.Health
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScreenControlMonitorTest {
    private var enabled = true
    private var backends = listOf("portal", "shizuku")
    private val setup = mutableMapOf<String, String?>()
    private val probe = mutableMapOf<String, String?>()
    private var clock = 0L
    private val monitor =
        ScreenControlMonitor(
            enabled = { enabled },
            backends = { backends },
            setup = { setup[it] },
            probe = { probe[it] },
            label = { it.replaceFirstChar(Char::uppercase) },
            now = { ++clock },
        )

    private fun health() =
        monitor.status.value.routes
            .map { it.health }

    @Test
    fun showsBackendsInPreferenceOrder() =
        runTest {
            assertEquals(listOf("Portal", "Shizuku"), monitor.refresh().routes.map { it.name })
            backends = listOf("shizuku", "portal")
            assertEquals(listOf("Shizuku", "Portal"), monitor.refresh().routes.map { it.name })
            enabled = false
            assertFalse(monitor.refresh().enabled)
            assertEquals(ScreenControlStatus(), monitor.status.value)
        }

    @Test
    fun aSetUpBackendIsReadyUntilARealFailureAndReadyAgainAfterTheNextSuccess() =
        runTest {
            monitor.refresh()
            assertEquals(listOf(Health.READY, Health.READY), health())

            monitor.record("portal", "Portal couldn't read the screen: no root node")
            val failedAt = clock
            val failed =
                monitor.status.value.routes
                    .first()
            assertEquals(Health.UNHEALTHY, failed.health)
            assertEquals("Portal couldn't read the screen: no root node", failed.problem)
            assertEquals(failedAt, failed.sinceMillis)
            // A real failure still lets a task try the route, but a passing check does not clear it.
            assertEquals(0, monitor.status.value.preferred)
            monitor.refresh()
            assertEquals(Health.UNHEALTHY, health().first())

            monitor.record("portal", null)
            assertEquals(listOf(Health.READY, Health.READY), health())
            assertEquals(
                null,
                monitor.status.value.routes
                    .first()
                    .sinceMillis,
            )
        }

    @Test
    fun missingSetupIsItsOwnStateAndIsSkipped() =
        runTest {
            setup["portal"] = "Provision the Portal token."
            setup["shizuku"] = "Shizuku is not installed."
            monitor.refresh()
            assertEquals(listOf(Health.SETUP_NEEDED, Health.SETUP_NEEDED), health())
            assertEquals(null, monitor.status.value.preferred)
            setup.remove("shizuku")
            monitor.refresh()
            assertEquals(1, monitor.status.value.preferred)
        }

    @Test
    fun aFailingCheckDegradesOnlyWhenNothingWorkedSinceItStartedFailing() =
        runTest {
            probe["portal"] = "Portal is not running."
            monitor.refresh()
            assertEquals(Health.DEGRADED, health().first())
            assertEquals(1, monitor.status.value.preferred)

            monitor.record("portal", null)
            monitor.refresh()
            assertEquals(Health.READY, health().first())

            probe.remove("portal")
            monitor.refresh()
            probe["portal"] = "Portal is not running."
            monitor.refresh()
            assertEquals(Health.DEGRADED, health().first())
        }
}
