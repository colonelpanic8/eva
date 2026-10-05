package com.colonelpanic.eva.devicecontrol

import android.content.ComponentName
import android.provider.Settings
import com.colonelpanic.eva.adapters.android.DeviceControlHost
import com.colonelpanic.eva.devicecontrol.portal.PortalHealth
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private class FakeShizuku(
    var status: String = DeviceControlHost.ALLOWED,
) : ShizukuHelper {
    val enabled = mutableListOf<Pair<ComponentName, Boolean>>()
    var restarts = 0

    override suspend fun accessStatus() = status

    override suspend fun requestAccess() = status

    override suspend fun restartHelper() {
        restarts++
    }

    override suspend fun enableAccessibilityService(
        component: ComponentName,
        restart: Boolean,
    ) {
        enabled += component to restart
    }
}

@RunWith(RobolectricTestRunner::class)
class ScreenControlRepairTest {
    private val portal = ComponentName("com.mobilerun.portal", "com.mobilerun.portal.service.MobilerunAccessibilityService")
    private val shizuku = FakeShizuku()
    private val verified = mutableListOf<String>()
    private var taskRunning = false

    private fun repair(
        health: () -> PortalHealth?,
        service: PortalService? = PortalService(portal, enabled = false),
    ) = ScreenControlRepair(
        context = RuntimeEnvironment.getApplication(),
        helper = { shizuku },
        portalHealth = health,
        portalPort = { 8080 },
        verify = { backend ->
            verified += backend
            null
        },
        taskRunning = { taskRunning },
        portalService = { service },
        portalStartMillis = 2_000,
    )

    @Test
    fun `a stopped Portal is restarted through Shizuku and confirmed by a screen read`() =
        runTest {
            var checks = 0
            val outcome =
                repair({ if (checks++ < 2) PortalHealth.UNREACHABLE else PortalHealth.READY }, PortalService(portal, enabled = true))
                    .repair("portal")

            assertEquals(listOf(portal to true), shizuku.enabled)
            assertEquals(listOf("portal"), verified)
            assertEquals(RepairOutcome.Fixed("Restarted Portal's accessibility service. It read the screen."), outcome)
        }

    @Test
    fun `without Shizuku a stopped Portal sends the user to accessibility settings`() =
        runTest {
            shizuku.status = DeviceControlHost.NOT_ALLOWED
            val outcome = repair({ PortalHealth.UNREACHABLE }).repair("portal")

            assertTrue(shizuku.enabled.isEmpty())
            assertTrue(outcome is RepairOutcome.Open)
            assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, (outcome as RepairOutcome.Open).intent.action)
        }

    @Test
    fun `a Portal that stays silent after its service is turned on is not reported fixed`() =
        runTest {
            val outcome = repair({ PortalHealth.UNREACHABLE }).repair("portal")

            assertEquals(listOf(portal to false), shizuku.enabled)
            assertTrue(verified.isEmpty())
            assertTrue(outcome.message, outcome.message.contains("still not answering on port 8080"))
        }

    @Test
    fun `the Shizuku helper is not restarted under a running device task`() =
        runTest {
            taskRunning = true
            val outcome = repair({ PortalHealth.READY }).repair("shizuku")

            assertEquals(0, shizuku.restarts)
            assertTrue(outcome is RepairOutcome.Failed)
        }
}
